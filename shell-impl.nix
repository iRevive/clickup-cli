{ pkgs }:

let
  lib = pkgs.lib;
  stdenv = pkgs.stdenv;
in
pkgs.hestia.shell.mkShell {
  name = "clickup-cli";

  buildInputs = [
    pkgs.s2n-tls
  ];
}
