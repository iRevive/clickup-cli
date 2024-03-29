{
  description = "clickup-cli";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/release-23.11";
    hestia.url = "github:iRevive/hestia-nix";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, flake-utils, hestia, ... }:
    flake-utils.lib.simpleFlake {
      inherit self nixpkgs;
      name = "clickup-cli";
      overlay = hestia.overlays.default;
      shell = ./shell-impl.nix;
    };
}
