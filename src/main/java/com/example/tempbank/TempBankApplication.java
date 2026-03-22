package com.example.tempbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TempBankApplication {

    public static void main(String[] args) {
        SpringApplication.run(TempBankApplication.class, args);
    }

//    @Bean
//    CommandLineRunner init(AccountRepository accountRepository) {
//        return args -> {
//            // 테스트 계좌 생성
//            Account account = Account.builder()
//                    .ownerName("홍길동")
//                    .initialBalance(new BigDecimal("100000"))
//                    .build();
//
//            accountRepository.save(account);
//
//            System.out.println("=== 계좌 생성 완료 ===");
//            System.out.println("계좌번호: " + account.getAccountNumber());
//            System.out.println("소유자: " + account.getOwnerName());
//            System.out.println("잔액: " + account.getBalance());
//        };
//    }
}