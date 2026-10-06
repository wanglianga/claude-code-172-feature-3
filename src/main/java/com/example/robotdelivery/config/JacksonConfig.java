package com.example.robotdelivery.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化配置：注册 Hibernate 模块，使 JPA 懒加载代理对象
 * （如 ManyToOne 关联未初始化时）能安全序列化，而不是抛出
 * ByteBuddyInterceptor 类型错误。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        return new Hibernate6Module();
    }
}
