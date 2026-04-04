{
  description = "unsquash — MCP tool for unfolding squashed diffs into logical commit sequences";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.babashka
            pkgs.clojure
            pkgs.git
            pkgs.just
            pkgs.stgit
          ];
        };
      });
}
