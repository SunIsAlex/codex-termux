use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rustc-check-cfg=cfg(codex_bazel)");
    println!("cargo:rerun-if-changed=src/grpc");
    println!("cargo:rerun-if-env-changed=PROTOC");

    let mut config = tonic_prost_build::Config::new();
    // Vendored protoc has no Android host executable. Native Termux builds use
    // the protobuf package; Linux cross-builds keep using the vendored compiler.
    let protoc = if let Some(path) = std::env::var_os("PROTOC") {
        PathBuf::from(path)
    } else if cfg!(target_os = "android") {
        PathBuf::from("protoc")
    } else {
        protoc_bin_vendored::protoc_bin_path()?
    };
    config.protoc_executable(protoc);
    let proto_files = glob::glob("src/grpc/*.proto")?.collect::<Result<Vec<_>, _>>()?;

    tonic_prost_build::configure()
        .build_client(/*enable*/ true)
        .build_server(/*enable*/ true)
        .compile_with_config(config, &proto_files, &[PathBuf::from("src/grpc")])?;

    Ok(())
}
