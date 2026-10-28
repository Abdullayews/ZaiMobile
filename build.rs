fn main() {
    // Şriftlər .slint faylında `import "…ttf"` ilə compile-time gömülür (theme.slint).
    // Burada yalnız resursların (şəkil PNG-ləri) binariyə gömülməsini təmin edirik.
    let config = slint_build::CompilerConfiguration::new()
        .embed_resources(slint_build::EmbedResourcesKind::EmbedFiles);
    slint_build::compile_with_config("ui/app.slint", config).expect("Slint UI kompilyasiyası");
    println!("cargo:rerun-if-changed=ui");
    println!("cargo:rerun-if-changed=assets");
}
