package com.coursework.cli;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        String backend = "http://localhost:8080";
        for (int i = 0; i + 1 < args.length; i++) if ("--backend".equals(args[i])) backend = args[i + 1];
        int code = new CliRunner(new HttpBackendGateway(backend), System.out).run(args);
        if (code != 0) System.exit(code);
    }
}
