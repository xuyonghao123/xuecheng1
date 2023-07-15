package com.xuecheng.ucenter.service;

import com.xuecheng.ucenter.model.po.XcUser;

/**
 * @author Xyh
 * @version 1.0
 * @description 微信认证接口
 * @date 2023/7/06 22:15
 */
public interface WxAuthService {
    /**
     * 申请令牌，根据令牌拿到用户信息，保存信息到数据库
     * code 授权码
     */
    public XcUser wxAuth(String code);
}
