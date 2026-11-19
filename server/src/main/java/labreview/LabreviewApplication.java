package labreview;

import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"labreview.order", "labreview.receipt", "labreview.document", "labreview.task"})
public class LabreviewApplication {
    public static void main(String[] args) {
        SpringApplication.run(LabreviewApplication.class, args);
    }

    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        var factory = new SqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(dataSource);
        return factory.getObject();
    }
}
