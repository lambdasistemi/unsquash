{
  description = "unsquash — MCP tool for unfolding squashed diffs into logical commit sequences";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    dev-assets-mkdocs.url = "github:paolino/dev-assets?dir=mkdocs";
  };

  outputs = { self, nixpkgs, flake-utils, dev-assets-mkdocs }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          inputsFrom = [
            dev-assets-mkdocs.devShells.${system}.default
          ];
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
