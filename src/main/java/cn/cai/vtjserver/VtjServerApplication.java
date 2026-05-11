package cn.cai.vtjserver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("cn.cai.vtjserver.mapper")
@SpringBootApplication
public class VtjServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VtjServerApplication.class, args);
    }

}
