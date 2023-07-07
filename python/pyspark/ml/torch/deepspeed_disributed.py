import subprocess
import shutil

KNOWN_HOSTS = "$HOME/.ssh/known_hosts"
TEMP_KNOWN_HOSTS = "$HOME/.ssh/known_hosts_original"


class DeepspeedDistributor(Distributor):
    def __init__(
        self,
        num_processes: int = 1,
        local_mode: bool = True,
        use_gpu: bool = True,
        deepspeed_config=None,
    ):
        super().__init__(num_processes, local_mode, use_gpu)
        self.deepspeed_config = deepspeed_config
        self.ssl_conf = "deepspeed.spark.distributor.ignoreSsl"
        self._validate_input_params()
        self.input_params = self._create_input_params()
        self.worker_hosts, self.hostfile_path = self._setup_hostfile_information()
        self.setup_env()

    def setup_env(self):
        try:
            subprocess.run("deepspeed --version".split())
            subprocess.run("ninja --version".split())
            with open("/root/.deepspeed_env", "w") as f:
                # if this is open; don't add that to path if they're not running on databricks
                f.write(
                    "PATH=/databricks/python3/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                )
        except:
            raise ImportError("Install deepspeed and ninja on the cluster using PyPi")

    def _setup_hostfile_information(self):
        # creating an SSH for the master node
        ssh_path = "/root/.ssh/id_rsa"
        ssh_pub_path = f"{ssh_path}.pub"

        if not os.path.exists(ssh_path):
            print(f"Creating {ssh_path}")
            result = subprocess.run(["ssh-keygen", "-t", "rsa", "-f", ssh_path, "-q", "-N", ""])
            if result.returncode != 0:
                raise RuntimeError("Failed to create key")
        else:
            print(f"{ssh_path} already exists")

        # writing to the authorized keys location
        with open(ssh_pub_path) as f:
            public_key = f.read()
        authorized_keys_path = "/root/.ssh/authorized_keys"
        with open(authorized_keys_path, "w") as f:
            print(f"Writing to {authorized_keys_path}")
            f.write(public_key)

        # sharing the same public key with the executors
        def save_to_executors():
            import os

            os.makedirs(os.path.dirname(authorized_keys_path), exist_ok=True)
            with open(authorized_keys_path, "w") as f:
                f.write(public_key)

        def _ssh_keyscan(ip_list):
            print("Starting to add worker ssh to known_hosts...")
            # create a copy of the file known_hosts to known_hosts_og
            shutil.copyfile(KNOWN_HOSTS, TEMP_KNOWN_HOSTS)
            # is responsible for adding public keys to the list of known_hosts
            for ip in ip_list:
                cmd = f"ssh-keyscan {ip} >> {KNOWN_HOSTS}"
                print("trying to run command")
                os.system(cmd)
            print("Finished adding workers ssh keys to known_hosts!")

        def _cleanup_known_hosts(self):
            # reset the known_hosts file; make this robust by dealing with errors
            os.remove(KNOWN_HOSTS)
            os.rename(KNOWN_HOSTS, TEMP_KNOWN_HOSTS)

        worker_hosts = [
            executor.host()
            for executor in self.spark.sparkContext._jsc.sc().statusTracker().getExecutorInfos()
        ]
        worker_count = len(worker_hosts) - 1
        rdd = spark.sparkContext.parallelize(range(worker_count), numSlices=worker_count)
        _ = rdd.mapPartitions(lambda _: [save_to_executors()]).collect()

        # getting the information back to the main driver
        gpus_per_worker = 1  # int(spark.conf.get("spark.executor.resource.gpu.amount"))
        hostfile_path = "/root/hostfile"
        print(f"Writing to {hostfile_path}")
        with open(hostfile_path, "w") as f:
            for worker_host in worker_hosts:
                line = f"{worker_host} slots={gpus_per_worker}"
                f.write(f"{line}\n")

        # print("SSH into these URLs and enter 'yes':", worker_hosts) # Mat: can we do something in the .ssh/config file to avoid this?
        # print(type(worker_hosts))
        _ssh_keyscan(worker_hosts)
        return worker_hosts, hostfile_path

    def _create_deepspeed_command(
        self, input_params: Dict[str, Any], path_to_train_file: str, *args: Any
    ):
        local_mode = input_params["local_mode"]
        num_processes = input_params["num_processes"]
        deepspeed_config = input_params["deepspeed_config"]
        if isinstance(deepspeed_config, dict):
            with tempfile.NamedTemporaryFile(mode="w+", delete=False, suffix=".json") as f:
                json.dump(deepspeed_config, f)
                deepspeed_config_path = f.name
        else:
            deepspeed_config_path = deepspeed_config
        if local_mode:
            # if local mode and num_nodes > 1, tell the user something is wrong with this
            # why is this not the same command normally used except remove some arguments
            deepspeed_args = [
                "--num_gpus",
                str(input_params["num_processes"]),
            ]  # no need for num nodes, the host file, or any port stuff (no communiation)
            # raise Exception("This is for you to implement lol; should be simple tbh")
        else:
            deepspeed_args = [
                "--num_gpus",
                str(input_params["num_processes"]),
                "--num_nodes",
                str(len(self.worker_hosts)),
                "--hostfile",
                str(self.hostfile_path),
                "--master_addr",
                str(self.worker_hosts[0]),
                "--master_port=9902",
            ]
        return [
            "deepspeed",
            *deepspeed_args,
            path_to_train_file,
            *args,
            "--deepspeed",
            "--deepspeed_config",
            deepspeed_config_path,
        ]

    def _run_training_on_pytorch_file(
        self, input_params: Dict[str, Any], train_path: str, *args: Any, **kwargs: Any
    ) -> None:
        if kwargs:
            raise ValueError("Running pytorch file does not support key-word type arguments.")
        training_command = self._create_deepspeed_command(input_params, train_path, *args)
        TorchDistributor._execute_command(training_command)

    def run(self, train_object: Union[Callable, str], *args: Any, **kwargs: Any) -> Optional[Any]:
        self._run_training_on_pytorch_file(self.input_params, train_object, *args, **kwargs)  # type: ignore
        return "Finished"


dist = DeepspeedDistributor(
    num_processes=1,
    use_gpu=True,
    local_mode=False,
    deepspeed_config="/dbfs/rithwik-db/deepspeed_files/ds_config.json",
)
dist.run("/dbfs/rithwik-db/deepspeed_files/cifar_deepspeed.py")
