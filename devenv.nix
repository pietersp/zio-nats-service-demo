{
  pkgs,
  lib,
  config,
  inputs,
  ...
}:

let
  pkgs-unstable = import inputs.nixpkgs-unstable {
    system = pkgs.stdenv.system;
    config.allowUnfree = true;
  };
in

{
  cachix.enable = true;

  packages = [
    pkgs.nil
    pkgs.git
    pkgs.natscli
    pkgs.nats-server
  ];

  claude.code.enable = true;

  languages.java.jdk.package = pkgs.jdk25_headless;
  languages.scala = {
    enable = true;
    lsp.enable = true;
    sbt.enable = true;
  };

  languages.nix = {
    enable = true;
  };

  # ---------------------------------------------------------------------------
  # Services
  # ---------------------------------------------------------------------------

  services.nats = {
    enable = true;
    jetstream.enable = true;
  };

  # ---------------------------------------------------------------------------
  # Scripts
  # ---------------------------------------------------------------------------

  scripts = {
    # Format all sources (main, test, and sbt files)
    format.exec = "sbt scalafmtAll";

    # Verify formatting and compile everything without running tests
    check.exec = "sbt scalafmtCheckAll compile";

    # Build fat JARs for service and client
    build.exec = "sbt assembly";

    # Run the user-service (requires NATS: devenv up [-d] nats)
    run-service.exec = "sbt userService/assembly && java -jar user-service/target/scala-3.3.7/user-service.jar";

    # Run the load-simulation client (requires NATS + user-service running)
    run-client.exec = "sbt userClient/assembly && java -jar user-client/target/scala-3.3.7/user-client.jar";
  };

  # ---------------------------------------------------------------------------
  # Git hooks
  # ---------------------------------------------------------------------------

  pre-commit.hooks.scalafmt-check = {
    enable = true;
    name = "scalafmt";
    entry = "sbt scalafmtCheckAll";
    language = "system";
    pass_filenames = false;
    files = "\\.(scala|sbt)$";
  };

  # ---------------------------------------------------------------------------
  # Shell
  # ---------------------------------------------------------------------------

  enterShell = ''
    echo "zio-nats-service-demo dev environment"
    echo ""
    echo "Scripts:"
    echo "  format        format all sources"
    echo "  check         verify formatting + compile (no tests)"
    echo "  build         build fat JARs (user-service.jar, user-client.jar)"
    echo "  run-service   start the user-service  (requires NATS)"
    echo "  run-client    start the load client   (requires NATS + service)"
    echo ""
    echo "Services:"
    echo "  devenv up [-d] nats    start NATS + JetStream on :4222"
    echo "  devenv processes down  stop running services"
    echo ""
    echo "Other tools:  nats  nats-server"
  '';

  # ---------------------------------------------------------------------------
  # CI / devenv test
  # ---------------------------------------------------------------------------

  enterTest = ''
    sbt compile
  '';
}
