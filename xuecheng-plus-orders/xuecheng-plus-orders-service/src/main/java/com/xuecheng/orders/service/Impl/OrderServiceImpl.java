package com.xuecheng.orders.service.Impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.base.execption.XueChengPlusException;
import com.xuecheng.base.utils.IdWorkerUtils;
import com.xuecheng.base.utils.QRCodeUtil;
import com.xuecheng.messagesdk.model.po.MqMessage;
import com.xuecheng.messagesdk.service.MqMessageService;
import com.xuecheng.orders.config.AlipayConfig;
import com.xuecheng.orders.config.PayNotifyConfig;
import com.xuecheng.orders.mapper.XcOrdersGoodsMapper;
import com.xuecheng.orders.mapper.XcOrdersMapper;
import com.xuecheng.orders.mapper.XcPayRecordMapper;
import com.xuecheng.orders.model.dto.AddOrderDto;
import com.xuecheng.orders.model.dto.PayRecordDto;
import com.xuecheng.orders.model.dto.PayStatusDto;
import com.xuecheng.orders.model.po.XcOrders;
import com.xuecheng.orders.model.po.XcOrdersGoods;
import com.xuecheng.orders.model.po.XcPayRecord;
import com.xuecheng.orders.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sun.text.resources.CollationData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private XcOrdersMapper ordersMapper;
    @Autowired
    private XcOrdersGoodsMapper ordersGoodsMapper;
    @Autowired
    private XcPayRecordMapper payRecordMapper;
    @Autowired
    private OrderServiceImpl currentProxy;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private MqMessageService mqMessageService;

    @Value("${pay.alipay.APP_ID}")
    String APP_ID;

    @Value("${pay.alipay.APP_PRIVATE_KEY}")
    String APP_PRIVATE_KEY;

    @Value("${pay.alipay.ALIPAY_PUBLIC_KEY}")
    String ALIPAY_PUBLIC_KEY;
    @Value("${pay.qrcodeurl}")
    String qrcodeurl;

    @Transactional
    @Override
    public PayRecordDto createOrder(String userId, AddOrderDto addOrderDto) {
        //添加商品订单
        XcOrders xcOrders = saveXcOrders(userId, addOrderDto);
        //添加支付交易记录
        XcPayRecord payRecord = createPayRecord(xcOrders);
        Long payNo = payRecord.getPayNo();
        //生成二维码
        QRCodeUtil qrCodeUtil = new QRCodeUtil();
        String url = String.format(qrcodeurl, payNo);
        //生成二维码
        String qrCode = null;
        try {
            qrCode = qrCodeUtil.createQRCode(url, 200, 200);
        } catch (IOException e) {
            XueChengPlusException.cast("生成二维码出错");
        }

        PayRecordDto payRecordDto = new PayRecordDto();
        BeanUtils.copyProperties(payRecord,payRecordDto);
        payRecordDto.setQrcode(qrCode);
        return payRecordDto;
    }

    @Override
    public XcPayRecord getPayRecordByPayno(String payNo) {
        XcPayRecord xcPayRecord = payRecordMapper.selectOne(new LambdaQueryWrapper<XcPayRecord>().eq(XcPayRecord::getPayNo, payNo));
        return xcPayRecord;
    }


    @Transactional
    public XcOrders saveXcOrders(String userId, AddOrderDto addOrderDto){
        //幂等性处理
        XcOrders order = getOrderByBusinessId(addOrderDto.getOutBusinessId());
        if(order!=null){
            return order;
        }
        order = new XcOrders();
        //添加订单
        //生成订单号
        long orderId = IdWorkerUtils.getInstance().nextId();
        order.setId(orderId);
        order.setTotalPrice(addOrderDto.getTotalPrice());
        order.setCreateDate(LocalDateTime.now());
        order.setStatus("600001");//未支付
        order.setUserId(userId);
        order.setOrderType(addOrderDto.getOrderType());
        order.setOrderName(addOrderDto.getOrderName());
        order.setOrderDescrip(addOrderDto.getOrderDescrip());
        order.setOrderDetail(addOrderDto.getOrderDetail());
        order.setOutBusinessId(addOrderDto.getOutBusinessId()); //选课记录id

        ordersMapper.insert(order);
        //添加订单明细
        String orderDetailJson = addOrderDto.getOrderDetail();
        List<XcOrdersGoods> xcOrdersGoods = JSON.parseArray(orderDetailJson, XcOrdersGoods.class);
        if (xcOrdersGoods.size()>0){
            for (XcOrdersGoods xcOrdersGood : xcOrdersGoods) {
                XcOrdersGoods xcOrdersGoods1 = new XcOrdersGoods();
                BeanUtils.copyProperties(xcOrdersGood,xcOrdersGoods1);
                xcOrdersGoods1.setOrderId(orderId);
                int insert = ordersGoodsMapper.insert(xcOrdersGoods1);
                log.info("订单明细表添加"+insert);
            }
        }
        return order;

    }

    //根据业务id查询订单
    public XcOrders getOrderByBusinessId(String businessId) {
        XcOrders orders = ordersMapper.selectOne(new LambdaQueryWrapper<XcOrders>().eq(XcOrders::getOutBusinessId, businessId));
        return orders;
    }

    public XcPayRecord createPayRecord(XcOrders orders){
        Long id = orders.getId();
        XcOrders xcOrders = ordersMapper.selectById(id);
        if(xcOrders==null){
            XueChengPlusException.cast("订单不存在");
        }
        if(xcOrders.getStatus().equals("600002")){
            XueChengPlusException.cast("订单已支付");
        }
        XcPayRecord payRecord = new XcPayRecord();
        //生成支付交易流水号
        long payNo = IdWorkerUtils.getInstance().nextId();
        payRecord.setPayNo(payNo);
        payRecord.setOrderId(id);//商品订单号
        payRecord.setOrderName(xcOrders.getOrderName());
        payRecord.setTotalPrice(xcOrders.getTotalPrice());
        payRecord.setCurrency("CNY");
        payRecord.setCreateDate(LocalDateTime.now());
        payRecord.setStatus("601001");//未支付
        payRecord.setUserId(xcOrders.getUserId());
        int insert = payRecordMapper.insert(payRecord);
        if (insert<0){
            XueChengPlusException.cast("插入支付记录表失败"+insert);
        }
        return payRecord;
    }

    @Override
    public PayRecordDto queryPayResult(String payNo) {
        XcPayRecord payRecord = getPayRecordByPayno(payNo);
        if (payRecord == null){
            XueChengPlusException.cast("请重新点击支付获取二维码");
        }
        //支付状态
        String status = payRecord.getStatus();
        //如果支付成功直接返回
        if ("601002".equals(status)){
            PayRecordDto payRecordDto = new PayRecordDto();
            BeanUtils.copyProperties(payRecord,payRecordDto);
            return payRecordDto;
        }
        //从支付宝查询支付结果
        PayStatusDto payStatusDto = queryPayResultFromAlipay(payNo);
        //保存支付结果
        currentProxy.saveAliPayStatus(payStatusDto);
        //重新查询支付记录
        payRecord = getPayRecordByPayno(payNo);
        PayRecordDto payRecordDto = new PayRecordDto();
        BeanUtils.copyProperties(payRecord,payRecordDto);
        return payRecordDto;
    }

    @Override
    public void saveAliPayStatus(PayStatusDto payStatusDto) {
        //支付流水号
        String payNo = payStatusDto.getOut_trade_no();
        XcPayRecord payRecord = getPayRecordByPayno(payNo);
        if (payRecord == null){
            XueChengPlusException.cast("支付记录找不到");
        }
        //拿到相关联的订单id
        Long orderId = payRecord.getOrderId();
        XcOrders xcOrders = ordersMapper.selectById(orderId);
        if (xcOrders == null){
            XueChengPlusException.cast("找不到相关联的订单");
        }
        //支付结果
        String trade_status = payStatusDto.getTrade_status();
        log.info("收到支付结果:{}，支付记录:{}",payStatusDto.toString(),payRecord.toString());
        if (trade_status.equals("TRADE_SUCCESS")){//支付宝返回的信息为支付成功
                payRecord.setStatus("601002");
                //支付宝的订单号
                payRecord.setOutPayNo(payStatusDto.getTrade_no());
                //第三方支付渠道编号
                payRecord.setOutPayChannel("Alipay");
                //支付成功的时间
                payRecord.setPaySuccessTime(LocalDateTime.now());
                payRecordMapper.updateById(payRecord);

                //更新订单表的状态为支付成功
            xcOrders.setStatus("600002");
            ordersMapper.updateById(xcOrders);

            //将消息写到数据库
            MqMessage mqMessage = mqMessageService.addMessage("payresult_notify", xcOrders.getOutBusinessId(), xcOrders.getOrderType(), null);
            //发送消息
            notifyPayResult(mqMessage);
        }
    }


    /**
     * 请求支付宝查询支付结果
     * @param payNo 支付交易号
     * @return 支付结果
     */
    public PayStatusDto queryPayResultFromAlipay(String payNo){
        //========请求支付宝查询支付结果=============
        AlipayClient alipayClient = new DefaultAlipayClient(AlipayConfig.URL, APP_ID, APP_PRIVATE_KEY, "json", AlipayConfig.CHARSET, ALIPAY_PUBLIC_KEY, AlipayConfig.SIGNTYPE); //获得初始化的AlipayClient
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        JSONObject bizContent = new JSONObject();
        bizContent.put("out_trade_no", payNo);
        request.setBizContent(bizContent.toString());
        AlipayTradeQueryResponse response = null;
        try {
            response = alipayClient.execute(request);
            if (!response.isSuccess()) {
                XueChengPlusException.cast("请求支付查询查询失败");
            }
        } catch (AlipayApiException e) {
            log.error("请求支付宝查询支付结果异常:{}", e.toString(), e);
            XueChengPlusException.cast("请求支付查询查询失败");
        }

        //获取支付结果
        String resultJson = response.getBody();
        //转map
        Map resultMap = JSON.parseObject(resultJson, Map.class);
        Map alipay_trade_query_response = (Map) resultMap.get("alipay_trade_query_response");
        //支付结果
        String trade_status = (String) alipay_trade_query_response.get("trade_status");
        String total_amount = (String) alipay_trade_query_response.get("total_amount");
        String trade_no = (String) alipay_trade_query_response.get("trade_no");
        //保存支付结果
        PayStatusDto payStatusDto = new PayStatusDto();
        payStatusDto.setOut_trade_no(payNo);
        payStatusDto.setTrade_status(trade_status);
        payStatusDto.setApp_id(APP_ID);
        payStatusDto.setTrade_no(trade_no);
        payStatusDto.setTotal_amount(total_amount);
        return payStatusDto;
    }

    @Override
    public void notifyPayResult(MqMessage message) {

        String josnString = JSON.toJSONString(message);
        //持久化消息
        Message msg = MessageBuilder.withBody(josnString.getBytes(StandardCharsets.UTF_8))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();

        Long id = message.getId();

        //全局消息id
        CorrelationData correlationData = new CorrelationData(id.toString());
        //使用correlationData指定回调方法
        correlationData.getFuture().addCallback(result->{
            if (result.isAck()){
                //消息发送成功
                log.debug("通知支付结果消息发送成功, ID:{}", correlationData.getId());
                //删除消息表中的记录
                mqMessageService.completed(id);
            }else {
                //消息发送失败
                log.error("通知支付结果消息发送失败, ID:{}, 原因{}",correlationData.getId(), result.getReason());
            }
        },ex->{
            //发生异常
        });

        //发送消息
        rabbitTemplate.convertAndSend(PayNotifyConfig.PAYNOTIFY_EXCHANGE_FANOUT,"",msg,correlationData);

    }

}
