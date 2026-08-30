package com.rwa.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.rwa.mapper")
public class MybatisPlusConfig {
}
