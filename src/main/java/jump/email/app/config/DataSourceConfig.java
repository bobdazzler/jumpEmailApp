//package jump.email.app.config;
//
//import com.zaxxer.hikari.HikariConfig;
//import com.zaxxer.hikari.HikariDataSource;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Primary;
//
//import javax.sql.DataSource;
//@Slf4j
//@Configuration
//public class DataSourceConfig {
//
//    @Bean
//    @Primary
//    public DataSource dataSource() {
//        String rawUrl = System.getenv("DATABASE_URL");
//
//        if (rawUrl == null || rawUrl.isBlank()) {
//            throw new IllegalStateException("DATABASE_URL not set");
//        }
//
//        // Convert Fly URL → JDBC URL
//        String jdbcUrl = rawUrl.replace("postgres://", "jdbc:postgresql://");
//
//        // Ensure sslmode=require exactly once
//        if (jdbcUrl.contains("?")) {
//            if (!jdbcUrl.contains("sslmode=")) {
//                jdbcUrl = jdbcUrl + "&sslmode=require";
//            }
//        } else {
//            jdbcUrl = jdbcUrl + "?sslmode=require";
//        }
//
//        HikariConfig config = new HikariConfig();
//        config.setJdbcUrl(jdbcUrl);
//        config.setDriverClassName("org.postgresql.Driver");
//        log.info("PostgreSQL JDBC URL: {}", jdbcUrl);
//        return new HikariDataSource(config);
//        //jdbc:postgresql://jump_email_sorter:HSxdKXmFyVsFLmv@lively-pine-6596.flycast:5432/jump_email_sorter?sslmode=disable
//    }
//}
