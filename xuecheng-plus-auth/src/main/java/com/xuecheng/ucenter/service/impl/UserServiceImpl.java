package com.xuecheng.ucenter.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.ucenter.mapper.XcMenuMapper;
import com.xuecheng.ucenter.mapper.XcUserMapper;
import com.xuecheng.ucenter.model.dto.AuthParamsDto;
import com.xuecheng.ucenter.model.dto.XcUserExt;
import com.xuecheng.ucenter.model.po.XcMenu;
import com.xuecheng.ucenter.model.po.XcUser;
import com.xuecheng.ucenter.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class UserServiceImpl implements UserDetailsService {

    @Autowired
    private XcUserMapper xcUserMapper;
    @Autowired
    private XcMenuMapper xcMenuMapper;
    @Autowired
    ApplicationContext applicationContext;
    @Override
    public UserDetails loadUserByUsername(String s) throws UsernameNotFoundException {
        AuthParamsDto authParamsDto = null;
        try {
            authParamsDto = JSON.parseObject(s, AuthParamsDto.class);
        } catch (Exception e) {
            throw new RuntimeException("请求认证参数不符合要求");
        }
        String authType = authParamsDto.getAuthType();
        //如果是账号密码认证
        String serviceImpl = authType + "_authservice";
        AuthService authService = applicationContext.getBean(serviceImpl, AuthService.class);
        XcUserExt user = authService.execute(authParamsDto);

        return getUserPrincipal(user);

    }

    public UserDetails getUserPrincipal(XcUserExt user){
        //加入用户权限，根据用户id查询用户权限
        //根据用户id查询用户权限
        String[] authoritie = {"p1"};
        List<XcMenu> xcMenus = xcMenuMapper.selectPermissionByUserId(user.getId());
        if (xcMenus.size()>0){
            List<String> permissions = new ArrayList<>();
            for (XcMenu xcMenu : xcMenus) {
                String code = xcMenu.getCode();
                permissions.add(code);
            }
            //将permissions转为数组
            authoritie = permissions.toArray(new String[0]);
        }
        String password = user.getPassword();
        //为了安全在令牌中不放密码
        user.setPassword(null);
        //将user转为json
        String userJson = JSONObject.toJSONString(user);
        UserDetails userDetails = User.withUsername(userJson).password(password).authorities(authoritie).build();
        //根据userDetails对象生成令牌
        return userDetails;
    }
}
