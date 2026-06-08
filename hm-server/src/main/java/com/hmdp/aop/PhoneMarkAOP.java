package com.hmdp.aop;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Aspect
@Slf4j
@Component
public class PhoneMarkAOP {
    @Pointcut("@annotation(com.hmdp.annotation.PhoneMarkAnnotation)")
    public void pointcut() {
    }

    @Around("pointcut()")
    public Object checkPhoneMark(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        if (args.length == 0) {
            return Result.fail("手机号参数缺失");
        }

        String phone;
        Object arg = args[0];
        if (arg instanceof String) {
            phone = (String) arg;
        } else if (arg instanceof LoginFormDTO) {
            phone = ((LoginFormDTO) arg).getPhone();
        } else {
            return Result.fail("手机号参数类型错误");
        }

        log.info("手机号验证：{}", phone);
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码格式无效");
        }
        return joinPoint.proceed();
    }
}
