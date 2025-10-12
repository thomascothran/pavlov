{ pkgs, lib, config, inputs, ... }:

{
  cachix.enable = false;

  # https://devenv.sh/packages/
  languages = {
    clojure.enable = true;
    javascript.enable = true;
  };

  packages = [
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

  enterShell = ''
    hello
    git --version
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
