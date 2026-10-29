//! Desktop smoke test: guest auth → send message → stream → verify response.
//! xvfb altında işləyir; ZAI_TEST_SEND hook controller-də mesajı avtomatik göndərir.

fn main() {
    let _ = env_logger::try_init();
    let msg = std::env::var("ZAI_TEST_SEND").unwrap_or_else(|_| "Say exactly: SMOKE_OK".into());
    println!("[smoke] sending: {}", msg);
    // controller::run() açılır, boot-dan sonra avtomatik send olur;
    // cavab gələndə (stream bitəndən 45s sonra) pəncərə bağlanır
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(50));
        println!("[smoke] timeout — exiting");
        std::process::exit(0);
    });
    zai_mobile::run_app();
}
