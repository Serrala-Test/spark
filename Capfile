require 'bundler/setup'
require 'capistrano_recipes/deploy/packserv'

set :application, "spark"
set :user, "deploy"
set :shared_work_path, "/u/apps/spark/shared/work"
set :shared_logs_path, "/u/apps/spark/shared/log"
set :shared_conf_path, "/u/apps/spark/shared/conf"
set :gateway, nil
set :keep_releases, 5

DATANODES = (2..47).map {|i| "dn%02d.chi.shopify.com" % i }
OTHERNODES = ["hadoop-etl1.chi.shopify.com", "spark-etl1.chi.shopify.com", "reports-reportify-etl3.chi.shopify.com", "reports-reportify-skydb4.chi.shopify.com", "platfora2.chi.shopify.com"]
BROKEN = ["dn16.chi.shopify.com"] # Node is down don't try to send code

task :production do
  role :app, *(DATANODES + OTHERNODES - BROKEN)
  role :history, "hadoop-rm.chi.shopify.com"
  role :uploader, "spark-etl1.chi.shopify.com"
end

namespace :deploy do
  task :cleanup do
    count = fetch(:keep_releases, 5).to_i
    run "ls -1dt /u/apps/spark/releases/* | tail -n +#{count + 1} | xargs rm -rf"
  end

  task :clear_hdfs_executables, :roles => :uploader, :on_no_matching_servers => :continue do
    count = fetch(:keep_releases, 5).to_i
    existing_releases = capture "hdfs dfs -ls hdfs://nn01.chi.shopify.com/user/sparkles/spark-assembly-*.jar | sort -k6 | sed 's/\\s\\+/ /g' | cut -d' ' -f8"
    existing_releases = existing_releases.split
    # hashtag paranoid. let the uploader overwrite the latest.jar
    existing_releases.reject! {|element| element.end_with?("spark-assembly-latest.jar")}
    if existing_releases.count > count
      existing_releases.shift(existing_releases.count - count).each do |path|
        run "hdfs dfs -rm -skipTrash #{path}"
      end
    end
  end

  task :upload_to_hdfs, :roles => :uploader, :on_no_matching_servers => :continue do
    run "hdfs dfs -copyFromLocal -f /u/apps/spark/current/lib/spark-assembly-*.jar hdfs://nn01.chi.shopify.com/user/sparkles/spark-assembly-#{fetch(:sha)}.jar"
  end

  task :litmus_test_sha_jar, :roles => :uploader, :on_no_master_servers => :continue do
    target_sha = ENV['SHA'] || `git rev-parse HEAD`.gsub(/\s/,"")
    run "sudo -u azkaban sh -c '. /u/virtualenvs/starscream/bin/activate && cd /u/apps/starscream/current && exec python shopify/tools/canary.py'"
  end

  task :prevent_gateway do
    set :gateway, nil
  end

  task :symlink_shared do
    run "ln -nfs #{shared_work_path} #{release_path}/work"
    run "ln -nfs #{shared_logs_path} #{release_path}/logs"
    run "rm -rf #{release_path}/conf && ln -nfs #{shared_conf_path} #{release_path}/conf"
  end

  task :remind_us_to_update_starscream do
    puts "****************************************************************"
    puts "*" 
    puts "*    Remember to update starscream/conf/config.yml"
    puts "*"
    puts "*    spark_production"
    puts "*      conf_options:"
    puts "*      <<: *spark_remote"
    puts "*      spark.yarn.jar: \"hdfs://nn01.chi.shopify.com:8020/user/sparkles/spark-assembly-\033[31m#{fetch(:sha)}\033[0m.jar\""
    puts "*"
    puts "****************************************************************"
  end
 

  task :restart do
  end

  after 'deploy:initialize_variables', 'deploy:prevent_gateway' # capistrano recipes packserv deploy always uses a gateway
  before  'deploy:symlink_current', 'deploy:symlink_shared'
  before 'deploy:upload_to_hdfs', 'deploy:clear_hdfs_executables'
  after  'deploy:download', 'deploy:upload_sha_jar', 'deploy:litmus_test_sha_jar'
  after 'deploy:restart', 'deploy:cleanup'
  after 'deploy:cleanup', 'deploy:remind_us_to_update_starscream'
end
