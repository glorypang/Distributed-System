# concurrent-test.ps1
Write-Host "=== 동시 송금 테스트 시작 ===" -ForegroundColor Green

# 계좌 2개 생성
Write-Host "`n[1/4] 계좌 생성 중..." -ForegroundColor Yellow

$account1Response = Invoke-RestMethod -Uri "http://localhost:8080/api/accounts" `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"ownerName": "홍길동", "initialBalance": 1000000}'

$account2Response = Invoke-RestMethod -Uri "http://localhost:8080/api/accounts" `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"ownerName": "김철수", "initialBalance": 1000000}'

$account1 = $account1Response.accountNumber
$account2 = $account2Response.accountNumber

Write-Host "계좌1: $account1 (잔액: 1,000,000)" -ForegroundColor Cyan
Write-Host "계좌2: $account2 (잔액: 1,000,000)" -ForegroundColor Cyan

# 동시 송금 실행
Write-Host "`n[2/4] 50개 송금 동시 실행..." -ForegroundColor Yellow

$jobs = @()
for ($i = 1; $i -le 50; $i++) {
    $jobs += Start-Job -ScriptBlock {
        param($from, $to)
        try {
            Invoke-RestMethod -Uri "http://localhost:8080/api/accounts/transfer" `
              -Method Post `
              -ContentType "application/json" `
              -Body "{`"fromAccount`": `"$from`", `"toAccount`": `"$to`", `"amount`": 1000}"
        } catch {
            Write-Error "송금 실패: $_"
        }
    } -ArgumentList $account1, $account2
}

Write-Host "Job 대기 중..." -ForegroundColor Yellow
$jobs | Wait-Job | Out-Null

Write-Host "`n[3/4] 결과 수집 중..." -ForegroundColor Yellow
$results = $jobs | Receive-Job
$jobs | Remove-Job

$successCount = ($results | Where-Object { $_.status -eq "SUCCESS" -or $_.status -eq "PENDING" }).Count
Write-Host "성공: $successCount / 50" -ForegroundColor Green

# 잔액 확인
Write-Host "`n[4/4] 최종 잔액 확인..." -ForegroundColor Yellow
Start-Sleep -Seconds 5 # Saga 완료 대기

$balance1 = Invoke-RestMethod -Uri "http://localhost:8080/api/accounts/$account1"
$balance2 = Invoke-RestMethod -Uri "http://localhost:8080/api/accounts/$account2"

Write-Host "`n=== 최종 결과 ===" -ForegroundColor Green
Write-Host "계좌1 ($account1)" -ForegroundColor Cyan
Write-Host "  현재 잔액: $($balance1.balance)" -ForegroundColor White
Write-Host "  예상 잔액: 950,000" -ForegroundColor Gray

Write-Host "`n계좌2 ($account2)" -ForegroundColor Cyan
Write-Host "  현재 잔액: $($balance2.balance)" -ForegroundColor White
Write-Host "  예상 잔액: 1,050,000" -ForegroundColor Gray

if ($balance1.balance -eq 950000 -and $balance2.balance -eq 1050000) {
    Write-Host "`n✅ 테스트 성공! 모든 송금이 정확히 처리되었습니다." -ForegroundColor Green
} else {
    Write-Host "`n⚠️ 잔액 불일치 발생!" -ForegroundColor Red
    Write-Host "차액: $([Math]::Abs(($balance1.balance + $balance2.balance) - 2000000))" -ForegroundColor Red
}

Write-Host "`n=== 테스트 완료 ===" -ForegroundColor Green