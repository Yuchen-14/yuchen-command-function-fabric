package com.yuchen.ycf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

public class YCFMod implements ModInitializer {

    private static final Map<String, CommandEntry> commandMap = new LinkedHashMap<>();
    private static int autoIdCounter = 0;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File dataFile;

    static class Variable {
        String name;
        String type;
        Variable(String name, String type) { this.name = name; this.type = type; }
    }

    static class CommandEntry {
        String id;
        String rawTemplate;
        List<Variable> variables;
        transient List<String> logicInfo;
        CommandEntry(String id, String rawTemplate, List<Variable> variables) {
            this.id = id;
            this.rawTemplate = rawTemplate;
            this.variables = variables;
            this.logicInfo = new ArrayList<>();
        }
    }

    static class StorageData {
        int autoIdCounter;
        Map<String, CommandEntry> commands;
    }

    @Override
    public void onInitialize() {
        dataFile = new File("config/ycf_commands.json");
        loadData();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("ycf")
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("template", StringArgumentType.greedyString())
                        .executes(ctx -> executeAdd(ctx.getSource(), StringArgumentType.getString(ctx, "template")))
                    )
                )
                .then(CommandManager.literal("use")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .then(CommandManager.argument("params", StringArgumentType.greedyString())
                            .executes(ctx -> executeUse(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                StringArgumentType.getString(ctx, "params")))
                        )
                        .executes(ctx -> executeUse(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id"),
                            ""))
                    )
                )
                .then(CommandManager.literal("del")
                    .then(CommandManager.argument("id", StringArgumentType.word())
                        .executes(ctx -> executeDel(ctx.getSource(), StringArgumentType.getString(ctx, "id")))
                    )
                )
                .then(CommandManager.literal("list")
                    .executes(ctx -> executeList(ctx.getSource()))
                )
            );
        });
    }

    private static int executeAdd(ServerCommandSource source, String template) {
        String possibleId = null;
        String[] parts = template.split(" ");
        String lastPart = parts[parts.length - 1];
        if (!lastPart.contains("<") && !lastPart.contains(">") && lastPart.matches("[a-zA-Z0-9_-]+")) {
            possibleId = lastPart;
            template = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
        }

        List<Variable> variables = new ArrayList<>();
        Pattern varPattern = Pattern.compile("<([a-zA-Z0-9_-]+)-([a-zA-Z]+)>");
        Matcher matcher = varPattern.matcher(template);
        Set<String> varNames = new HashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String type = matcher.group(2).toLowerCase();
            if (!isValidType(type)) {
                source.sendError(Text.of("未知的变量类型: " + type));
                return 0;
            }
            if (!varNames.add(name)) {
                source.sendError(Text.of("变量名重复: " + name));
                return 0;
            }
            variables.add(new Variable(name, type));
        }

        String id;
        if (possibleId != null && !possibleId.isEmpty()) {
            id = possibleId;
            if (commandMap.containsKey(id)) {
                source.sendError(Text.of("ID 已存在: " + id));
                return 0;
            }
        } else {
            id = String.valueOf(++autoIdCounter);
            while (commandMap.containsKey(id)) {
                id = String.valueOf(++autoIdCounter);
            }
        }

        CommandEntry entry = new CommandEntry(id, template, variables);
        commandMap.put(id, entry);
        saveData();

        source.sendFeedback(() -> Text.of("已添加命令，ID: " + id + " | 模板: " + template), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeUse(ServerCommandSource source, String id, String params) {
        CommandEntry entry = commandMap.get(id);
        if (entry == null) {
            source.sendError(Text.of("未找到 ID 为 " + id + " 的命令。"));
            return 0;
        }

        List<Variable> vars = entry.variables;
        String[] userParams = params.trim().isEmpty() ? new String[0] : params.split(" ");

        int expectedCount = 0;
        for (Variable v : vars) {
            expectedCount += v.type.equals("pos") ? 3 : 1;
        }
        if (userParams.length != expectedCount) {
            source.sendError(Text.of("参数数量错误。需要 " + expectedCount + " 个，提供了 " + userParams.length + " 个。"));
            return 0;
        }

        Map<String, String> replacements = new LinkedHashMap<>();
        int paramIndex = 0;
        try {
            for (Variable v : vars) {
                switch (v.type) {
                    case "user", "text", "item", "world" -> {
                        replacements.put(v.name, userParams[paramIndex]);
                        paramIndex++;
                    }
                    case "int" -> {
                        Integer.parseInt(userParams[paramIndex]);
                        replacements.put(v.name, userParams[paramIndex]);
                        paramIndex++;
                    }
                    case "float" -> {
                        Float.parseFloat(userParams[paramIndex]);
                        replacements.put(v.name, userParams[paramIndex]);
                        paramIndex++;
                    }
                    case "bool" -> {
                        String b = userParams[paramIndex];
                        if (!b.equalsIgnoreCase("true") && !b.equalsIgnoreCase("false"))
                            throw new IllegalArgumentException("布尔值必须为 true 或 false");
                        replacements.put(v.name, b);
                        paramIndex++;
                    }
                    case "pos" -> {
                        String x = userParams[paramIndex++];
                        String y = userParams[paramIndex++];
                        String z = userParams[paramIndex++];
                        Double.parseDouble(x); Double.parseDouble(y); Double.parseDouble(z);
                        replacements.put(v.name, x + " " + y + " " + z);
                    }
                    default -> {
                        replacements.put(v.name, userParams[paramIndex]);
                        paramIndex++;
                    }
                }
            }
        } catch (NumberFormatException e) {
            source.sendError(Text.of("数字格式错误。"));
            return 0;
        } catch (IllegalArgumentException e) {
            source.sendError(Text.of(e.getMessage()));
            return 0;
        }

        String command = entry.rawTemplate;
        for (Map.Entry<String, String> rep : replacements.entrySet()) {
            command = command.replaceAll("<" + Pattern.quote(rep.getKey()) + "-[a-zA-Z]+>", rep.getValue());
        }

        source.getServer().getCommandManager().executeWithPrefix(source.getServer().getCommandSource(), command);
        source.sendFeedback(() -> Text.of("命令已执行。"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int executeDel(ServerCommandSource source, String id) {
        if (commandMap.remove(id) != null) {
            saveData();
            source.sendFeedback(() -> Text.of("已删除命令 ID: " + id), false);
        } else {
            source.sendError(Text.of("未找到 ID 为 " + id + " 的命令。"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int executeList(ServerCommandSource source) {
        if (commandMap.isEmpty()) {
            source.sendFeedback(() -> Text.of("当前没有保存任何命令。"), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendFeedback(() -> Text.of("=== 已保存的命令列表 ==="), false);
        for (CommandEntry entry : commandMap.values()) {
            String info = "[" + entry.id + "] " + entry.rawTemplate;
            if (!entry.variables.isEmpty()) {
                StringBuilder sb = new StringBuilder("    变量: ");
                for (Variable v : entry.variables) {
                    sb.append("<").append(v.name).append("-").append(v.type).append("> ");
                }
                String finalInfo = info;
                String finalVars = sb.toString().trim();
                source.sendFeedback(() -> Text.of(finalInfo + "\n" + finalVars), false);
            } else {
                String finalInfo = info;
                source.sendFeedback(() -> Text.of(finalInfo), false);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static boolean isValidType(String type) {
        return Arrays.asList("user", "text", "int", "float", "pos", "item", "world", "bool").contains(type);
    }

    private static void loadData() {
        if (dataFile.exists()) {
            try (Reader reader = new FileReader(dataFile)) {
                StorageData data = GSON.fromJson(reader, StorageData.class);
                if (data != null) {
                    autoIdCounter = data.autoIdCounter;
                    commandMap.clear();
                    if (data.commands != null) {
                        commandMap.putAll(data.commands);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void saveData() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            StorageData data = new StorageData();
            data.autoIdCounter = autoIdCounter;
            data.commands = new LinkedHashMap<>(commandMap);
            try (Writer writer = new FileWriter(dataFile)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
