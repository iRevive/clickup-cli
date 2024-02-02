{
  description = "clickup-cli";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/39d8adc12a930db5abd121a7169ddc85b49f17a0";
    flake-utils.url = "github:numtide/flake-utils/c0e246b9b83f637f4681389ecabcb2681b4f3af0";
    sbt-derivation = {
      url = "github:zaninime/sbt-derivation/fe0044d2cd351f4d6257956cde3a2ef633d33616";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, sbt-derivation }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        clickup-cli = import ./default.nix { pkgs = pkgs; sbt-derivation = sbt-derivation;};
      in {
        packages.clickup-cli = clickup-cli;
        defaultPackage = clickup-cli;
      }
    );
}
