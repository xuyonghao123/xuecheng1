package com.xuecheng.learning.service.Impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.Channel;
import com.xuecheng.base.execption.XueChengPlusException;
import com.xuecheng.learning.config.PayNotifyConfig;
import com.xuecheng.learning.service.MyCourseTablesService;
import com.xuecheng.messagesdk.model.po.MqMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ReceivePayNotifyService {
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private MyCourseTablesService myCourseTablesService;

    @RabbitListener(queues = PayNotifyConfig.PAYNOTIFY_QUEUE)
    public void receive(Message message, Channel channel){
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //解析出消息
        byte[] body = message.getBody();
        String json = new String(body);
        //转成对象
        MqMessage mqMessage = JSON.parseObject(json, MqMessage.class);

        //根据消息内容，更新选课记录，向我的课程表新增课程
        String messageType = mqMessage.getMessageType();
        //选课id
        String courseId = mqMessage.getBusinessKey1();
        //订单类型
        String businessKey2 = mqMessage.getBusinessKey2();
        if (messageType.equals(PayNotifyConfig.MESSAGE_TYPE) && "60201".equals(businessKey2)){
            boolean b = myCourseTablesService.saveChooseCourseStauts(courseId);
            if(!b){
                //添加选课失败，抛出异常，消息重回队列
                XueChengPlusException.cast("收到支付结果，添加选课失败");
            }
        }

    }

}
