package com.xuecheng.ucenter.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.ucenter.mapper.XcUserMapper;
import com.xuecheng.ucenter.mapper.XcUserRoleMapper;
import com.xuecheng.ucenter.model.dto.AuthParamsDto;
import com.xuecheng.ucenter.model.dto.XcUserExt;
import com.xuecheng.ucenter.model.po.XcUser;
import com.xuecheng.ucenter.model.po.XcUserRole;
import com.xuecheng.ucenter.service.AuthService;
import com.xuecheng.ucenter.service.WxAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service("wx_authservice")
public class WxAuthServiceImpl implements AuthService, WxAuthService {
    @Autowired
    private XcUserMapper xcUserMapper;
    @Autowired
    private XcUserRoleMapper xcUserRoleMapper;
    @Autowired
    private WxAuthServiceImpl currentProxy;
    @Autowired
    private RestTemplate restTemplate;
    @Value("${weixin.appid}")
    String appid;
    @Value("${weixin.secret}")
    String secret;
    @Override
    public XcUserExt execute(AuthParamsDto authParamsDto) {
        //获取登录名称
        String username = authParamsDto.getUsername();

        XcUser xcUser = xcUserMapper.selectOne(new LambdaQueryWrapper<XcUser>().eq(XcUser::getUsername, username));

        if (xcUser ==null){
            //返回空表示用户不存在
            throw new RuntimeException("账号不存在");
        }
        XcUserExt xcUserExt = new XcUserExt();
        BeanUtils.copyProperties(xcUser,xcUserExt);
        return xcUserExt;
    }

    @Override
    public XcUser wxAuth(String code) {
        //申请令牌
        Map<String, String> accessToken = getAccess_token(code);
        if (accessToken == null){
            return null;
        }
        log.info("申请的令牌信息:{}",accessToken);
        String openid = accessToken.get("openid");
        String access_token = accessToken.get("access_token");
        // 根据令牌拿到用户信息
        Map<String, String> userinfo = getUserinfo(access_token, openid);
        if (userinfo == null){
            return null;
        }
        // 保存信息到数据库
        XcUser xcUser = currentProxy.addWxUser(userinfo);
        return xcUser;
    }


    @Transactional
    public XcUser addWxUser(Map userinfo){
        String unionid = userinfo.get("unionid").toString();
        //根据unionid查询数据库
        XcUser xcUser = xcUserMapper.selectOne(new LambdaQueryWrapper<XcUser>().eq(XcUser::getWxUnionid, unionid));
        if (xcUser != null){
            return xcUser;
        }
        String userId = UUID.randomUUID().toString();
        xcUser= new XcUser();
        xcUser.setId(userId);
        xcUser.setWxUnionid(unionid);
        //记录从微信得到的昵称
        xcUser.setNickname(userinfo.get("nickname").toString());
        xcUser.setUserpic(userinfo.get("headimgurl").toString());
        xcUser.setUsername(unionid);
        xcUser.setName(userinfo.get("nickname").toString());
        xcUser.setPassword(unionid);
        xcUser.setUtype("101001"); //学生类型
        xcUser.setStatus("1"); //用户状态
        xcUser.setCreateTime(LocalDateTime.now());
        xcUserMapper.insert(xcUser);
        XcUserRole xcUserRole = new XcUserRole();
        xcUserRole.setId(UUID.randomUUID().toString());
        xcUserRole.setUserId(userId);
        xcUserRole.setRoleId("17"); //学生角色
        xcUserRoleMapper.insert(xcUserRole);
        return xcUser;

    }

    /**
     * 申请访问令牌,响应示例
     {
     "access_token":"ACCESS_TOKEN",
     "expires_in":7200,
     "refresh_token":"REFRESH_TOKEN",
     "openid":"OPENID",
     "scope":"SCOPE",
     "unionid": "o6_bmasdasdsad6_2sgVt7hMZOPfL"
     }
     */
    private Map<String,String> getAccess_token(String code) {
        String wxUrl_template = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code";
        String wxUrl = String.format(wxUrl_template, appid, secret, code);
        log.info("调用微信接口申请access_token, url:{}", wxUrl);
        ResponseEntity<String> exchange = restTemplate.exchange(wxUrl, HttpMethod.POST, null, String.class);

        String body = exchange.getBody();
        log.info("调用微信接口申请access_token: 返回值:{}", body);
        Map<String,String> map = JSON.parseObject(body, Map.class);
        return map;
    }


    /**获取用户信息，示例如下：
     {
     "openid":"OPENID",
     "nickname":"NICKNAME",
     "sex":1,
     "province":"PROVINCE",
     "city":"CITY",
     "country":"COUNTRY",
     "headimgurl": "https://thirdwx.qlogo.cn/mmopen/g3MonUZtNHkdmzicIlibx6iaFqAc56vxLSUfpb6n5WKSYVY0ChQKkiaJSgQ1dZuTOgvLLrhJbERQQ4eMsv84eavHiaiceqxibJxCfHe/0",
     "privilege":[
     "PRIVILEGE1",
     "PRIVILEGE2"
     ],
     "unionid": " o6_bmasdasdsad6_2sgVt7hMZOPfL"
     }
     */
    private Map<String,String> getUserinfo(String access_token,String openid) {
        String wxUrl_template = "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s";
        String wxUrl = String.format(wxUrl_template, access_token, openid);

        log.info("调用微信接口申请access_token, url:{}", wxUrl);
        ResponseEntity<String> exchange = restTemplate.exchange(wxUrl, HttpMethod.POST, null, String.class);

        //防止乱码进行转码
        String result = new String(exchange.getBody().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        log.info("调用微信接口申请access_token:返回值:{}",result);
        Map<String,String> map = JSON.parseObject(result, Map.class);

        return map;
    }
}
