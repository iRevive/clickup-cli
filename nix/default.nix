{ pkgs, sbt-derivation }:

let
  version = "0.0.1";
  pname = "clickup-cli";
  src = ./../.;
  completions = ./completions.zsh;
in
sbt-derivation.lib.mkSbtDerivation {
  inherit pkgs version pname src;

  depsSha256 = "sha256-uKYEPUUsGBMmvCxlfXY2u45BsKQ6y9d2R1wNqqYHlIk";

  nativeBuildInputs = [
    pkgs.s2n-tls
    pkgs.which
    pkgs.installShellFiles
  ];

  buildPhase = ''
    sbt generateNativeBinary
  '';

  installPhase = ''
    mkdir -p $out/bin
    cp clickup-cli $out/bin/clickup-cli
    chmod 0755 $out/bin/clickup-cli
    installShellCompletion --name _${pname} --zsh ${completions}
 '';
}
