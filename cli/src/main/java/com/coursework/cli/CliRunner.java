package com.coursework.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.UUID;

public final class CliRunner {
    private final BackendGateway gateway;
    private final PrintStream out;
    private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

    public CliRunner(BackendGateway gateway, PrintStream out) { this.gateway = gateway; this.out = out; }

    public int run(String[] rawArgs) {
        try {
            Arguments args = Arguments.parse(rawArgs);
            if (args.command() == null || "help".equals(args.command())) { printHelp(); return 0; }
            switch (args.command()) {
                case "health" -> print("health", gateway.health());
                case "stats" -> print("stats", gateway.stats());
                case "index" -> print("index", gateway.index(args.threads()));
                case "search" -> runSearch(args);
                default -> throw new IllegalArgumentException("Невідома команда: " + args.command());
            }
            return 0;
        } catch (IllegalArgumentException e) { out.println("Помилка параметрів: " + e.getMessage()); printHelp(); return 2; }
        catch (BackendException e) { out.println("Backend повернув HTTP " + e.statusCode() + ": " + e.getMessage()); return 3; }
        catch (Exception e) { out.println("Не вдалося виконати запит: " + (e.getMessage() == null ? e : e.getMessage())); return 4; }
    }

    private void runSearch(Arguments args) throws Exception {
        if (args.live()) {
            String requestId = UUID.randomUUID().toString();
            gateway.searchLive(requestId, args.query(), args.mode(), event -> printEvent(event));
        } else print("search", gateway.search(args.query(), args.mode()));
    }
    private void print(String operation, JsonNode value) { out.println("== " + operation + " =="); out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private void printEvent(JsonNode event) {
        String stage = event.path("stage").asText("unknown");
        out.println("[" + stage + "] " + event.path("data").toString());
    }
    private void printHelp() {
        out.println("Usage: java -jar parallel-search-cli.jar [--backend URL] <command> [options]");
        out.println("  health                         перевірити backend та Ollama");
        out.println("  index [--threads N]           побудувати індекс (типово 4 потоки)");
        out.println("  search --query TEXT [--mode keyword|llm] [--live]  виконати пошук");
        out.println("  stats                          показати статистику індексу");
        out.println("  help                           показати цю довідку");
    }

    record Arguments(String command, String backend, int threads, String query, String mode, boolean live) {
        static Arguments parse(String[] input) {
            String command = null, backend = "http://localhost:8080", query = null, mode = "keyword"; int threads = 4; boolean live = false;
            for (int i = 0; i < input.length; i++) {
                String arg = input[i];
                if ("--backend".equals(arg)) backend = next(input, ++i, "backend URL");
                else if (command == null && !arg.startsWith("--")) command = arg;
                else if ("--threads".equals(arg)) { threads = parsePositive(next(input, ++i, "threads"), "threads"); }
                else if ("--query".equals(arg)) query = next(input, ++i, "query");
                else if ("--mode".equals(arg)) mode = next(input, ++i, "mode");
                else if ("--live".equals(arg)) live = true;
                else throw new IllegalArgumentException("невідомий параметр: " + arg);
            }
            if ("search".equals(command) && (query == null || query.isBlank())) throw new IllegalArgumentException("для search потрібен --query");
            if ("search".equals(command) && !mode.equals("keyword") && !mode.equals("llm")) throw new IllegalArgumentException("mode має бути keyword або llm");
            return new Arguments(command, backend, threads, query, mode, live);
        }
        private static String next(String[] args, int index, String name) { if (index >= args.length || args[index].startsWith("--")) throw new IllegalArgumentException("потрібно вказати " + name); return args[index]; }
        private static int parsePositive(String value, String name) { try { int n = Integer.parseInt(value); if (n < 1 || n > 64) throw new IllegalArgumentException(name + " має бути від 1 до 64"); return n; } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " має бути цілим числом"); } }
    }
}
