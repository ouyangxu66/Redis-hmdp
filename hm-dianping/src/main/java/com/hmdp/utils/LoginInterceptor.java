package com.hmdp.utils;


import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null){
            //不存在用户,需要拦截,设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        return true;
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除当前用户
        UserHolder.removeUser();
    }


}
