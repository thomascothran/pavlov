{ pkgs, lib, config, inputs, ... }:

let
  clml = inputs.clojure-mcp-light;

  # clojure-mcp-light's bb.edn plus the Maven mirrors from deps.edn, so the
  # clj-paren-repair and clj-nrepl-eval scripts fetch their deps from Google's
  # Maven Central mirror first. bb resolves :paths relative to the real location
  # of bb.edn, so it is copied next to a src symlink. Keep :deps in sync with
  # ${clml}/bb.edn.
  clmlBbEdn = pkgs.writeText "bb.edn" ''
    {:paths ["src"]
     :deps {parinferish/parinferish {:mvn/version "0.8.0"}
            dev.weavejester/cljfmt {:mvn/version "0.15.5"}}
     :mvn/repos {"central" {:url "https://maven-central.storage-download.googleapis.com/maven2/"}
                 "maven-central" {:url "https://repo1.maven.org/maven2/"}}}
  '';
  clmlBb = pkgs.runCommand "clojure-mcp-light-bb" { } ''
    mkdir $out
    cp ${clmlBbEdn} $out/bb.edn
    ln -s ${clml}/src $out/src
  '';

in

{
  cachix.enable = false;

  # No C/C++ toolchain (gcc, binutils, libc headers) in the shell.
  stdenv = pkgs.stdenvNoCC;

  # https://devenv.sh/packages/
  # Language servers are off; enable them in devenv.local.nix if your editor
  # uses them.
  languages = {
    clojure.enable = true;
    clojure.lsp.enable = false;
    java.lsp.enable = false;
    javascript.enable = true;
    javascript.lsp.enable = false;
  };

  packages = [
    pkgs.babashka
    pkgs.git
    pkgs.nodejs
  ];

  # https://devenv.sh/languages/
  # languages.rust.enable = true;

  # https://devenv.sh/processes/
  processes.clj.exec = "clj -A:dev:test -X dev/go!";

  # https://devenv.sh/services/
  # services.postgres.enable = true;

  # https://devenv.sh/scripts/
  scripts.cljs-test-watch.exec = ''
    node --watch out/node-tests.js
  '';
  scripts.deploy.exec = ''
    clj -X:test
    cd ./modules/pavlov
    clj -T:build ci
    env $(cat ~/.secrets/.clojars | xargs) clj -T:build deploy
    cd ../pavlov-devtools
    clj -T:build ci
    env $(cat ~/.secrets/.clojars | xargs) clj -T:build deploy
    cd ../pavlov-skills
    clj -T:build ci
    env $(cat ~/.secrets/.clojars | xargs) clj -T:build deploy
    cd ../pavlov-web
    clj -T:build ci
    env $(cat ~/.secrets/.clojars | xargs) clj -T:build deploy
  '';
  scripts.squint-watch.exec = ''
    npx squint watch
  '';
  scripts.clj-test.exec = ''
    clj -X:test
  '';
  scripts.test-watch.exec = ''
    clj -X:test :watch? true
  '';

  scripts.clj-paren-repair.exec = ''
    exec bb --config ${clmlBb}/bb.edn -m clojure-mcp-light.paren-repair "$@"
  '';

  scripts.clj-nrepl-eval.exec = ''
    exec bb --config ${clmlBb}/bb.edn -m clojure-mcp-light.nrepl-eval "$@"
  '';

  # https://devenv.sh/tasks/
  # tasks = {
  #   "myproj:setup".exec = "mytool build";
  #   "devenv:enterShell".after = [ "myproj:setup" ];
  # };

  # https://devenv.sh/tests/
  enterTest = ''
    echo "Running tests"
    git --version | grep --color=auto "${pkgs.git.version}"
  '';

  # https://devenv.sh/git-hooks/
  # git-hooks.hooks.shellcheck.enable = true;

  # See full reference at https://devenv.sh/reference/options/
}
