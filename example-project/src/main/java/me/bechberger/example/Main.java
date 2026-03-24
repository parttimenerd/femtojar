package me.bechberger.example;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(name = "demo-app", mixinStandardHelpOptions = true, description = "Demo app packaged as a combined JAR")
public class Main implements Callable<Integer> {

    @Option(names = "--repeats", defaultValue = "2000", description = "How many times to repeat the payload chunk")
    private int repeats;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        String payload = App.payload(repeats);
        System.out.println("payload-size=" + payload.length());
        return 0;
    }
}