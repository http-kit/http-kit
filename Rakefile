task :default => :test

desc "Run unit test"
task :test do
  sh 'rm classes -rf && lein javac && lein test'
end

desc "Install in local repository"
task :install_local do
  sh 'lein deps && rm *.jar pom.xml classes -rf && lein jar && lein install'
  sh 'cd ~/workspace/rssminer && lein deps'
end

desc "Install in clojars repository"
task :clojars do
  sh 'rm *.jar pom.xml classes -rf && lein pom && lein jar '
  sh 'scp pom.xml *.jar clojars@clojars.org:'
end

desc "Start swank server for emacs"
task :swank do
  sh "rm classes -rf && lein javac && lein swank"
end

desc "Benchmark to an idea how fast it can run"
task :bench do
  sh 'rm classes/ -rf && lein deps && lein javac'
  sh './scripts/benchmark bench'
end
