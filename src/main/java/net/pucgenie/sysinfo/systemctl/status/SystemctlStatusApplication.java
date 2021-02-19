package net.pucgenie.sysinfo.systemctl.status;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@lombok.extern.slf4j.Slf4j
public class SystemctlStatusApplication
implements org.springframework.boot.ApplicationRunner
{

	public static void main(String[] args) {
		SpringApplication.run(SystemctlStatusApplication.class, args);
	}

	@Override
	public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
		//log.debug("java.library.path: {}", System.getProperty("java.library.path"));
		boolean skipDefaultLib = args.getOptionValues("skipDefaultLib") != null;
		List<String> argLoadLibrary = args.getOptionValues("loadLibrary");
		if (argLoadLibrary != null) {
			Runtime.getRuntime().loadLibrary(argLoadLibrary.get(0));
			skipDefaultLib = true;
		}
		var argLoad = args.getOptionValues("load");
		if (argLoad != null) {
			Runtime.getRuntime().load(argLoad.get(0));
			skipDefaultLib = true;
		}
		if (!skipDefaultLib) {
			System.loadLibrary("unix-java");
		} else {
			log.info("skipped loading default library");
		}
	}

}
