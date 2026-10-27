package dev.lightning;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.lightning.capture.CaptureOptions;
import dev.lightning.capture.Recorder;
import dev.lightning.export.Exporter;
import java.util.function.BiFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /lightning profiler start|stop|cancel|status}. On a client the command runs locally (it
 * works on any server); on a dedicated server it needs operator permission.
 */
final class LightningCommands {
    private LightningCommands() {}

    /** How to tell the player about a capture that finishes later. */
    interface Feedback {
        void message(Component text);

        void saved(Exporter.Result result);
    }

    static void registerClient(CommandDispatcher<CommandSourceStack> dispatcher, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("lightning");
        root.then(profiler(feedback));
        extras(root, feedback);
        root.then(Commands.literal("open").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> LightningClient.fileAction("open", StringArgumentType.getString(c, "id")))));
        root.then(Commands.literal("download").then(Commands.argument("id", StringArgumentType.word())
                .executes(c -> LightningClient.fileAction("download", StringArgumentType.getString(c, "id")))));
        dispatcher.register(root);
    }

    static void registerServer(CommandDispatcher<CommandSourceStack> dispatcher) {
        BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback = (c, client) -> new Feedback() {
            private final CommandSourceStack source = c.getSource();

            @Override
            public void message(Component text) {
                source.getServer().execute(() -> source.sendSystemMessage(text));
            }

            @Override
            public void saved(Exporter.Result result) {
                message(Component.literal("⚡ Capture saved to " + result.archive()).withStyle(ChatFormatting.GOLD));
            }
        };
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("lightning").requires(s -> s.hasPermission(4));
        root.then(profiler(feedback));
        extras(root, feedback);
        dispatcher.register(root);
    }

    /** {@code /lightning mark <label>}, {@code /lightning heap} and {@code /lightning report}. */
    private static void extras(LiteralArgumentBuilder<CommandSourceStack> root, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        // All-in-one: record everything, then the full memory breakdown, in one report.
        root.then(Commands.literal("report")
                .executes(c -> start(c, "--all --timeout 60", feedback))
                .then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 1800))
                        .executes(c -> start(c, "--all --timeout " + com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "seconds"), feedback))));
        root.then(Commands.literal("mark").then(Commands.argument("label", StringArgumentType.greedyString()).executes(c -> {
            String label = StringArgumentType.getString(c, "label");
            boolean ok = Lightning.recorder().mark(label);
            feedback.apply(c, false).message(Component.literal(ok ? "⚡ Phase: " + label : "⚡ Not recording, so there is nothing to mark.")
                    .withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
            return ok ? 1 : 0;
        })));
        root.then(Commands.literal("heap").then(Commands.literal("deep").executes(c -> deep(c, feedback, false))
                .then(Commands.literal("keep").executes(c -> deep(c, feedback, true)))));
        root.then(Commands.literal("heap").executes(c -> {
            Feedback out = feedback.apply(c, false);
            out.message(Component.literal("⚡ Taking a memory snapshot (the game may pause for a moment)…").withStyle(ChatFormatting.GRAY));
            Lightning.recorder().heapSnapshot(
                    (line, file) -> out.message(Component.literal("⚡ " + line + ". Full list: " + file).withStyle(ChatFormatting.GOLD)),
                    error -> out.message(Component.literal("⚡ Memory snapshot failed: " + error).withStyle(ChatFormatting.RED)));
            return 1;
        }));
    }

    private static int deep(CommandContext<CommandSourceStack> c, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback, boolean keep) {
        Feedback out = feedback.apply(c, false);
        out.message(Component.literal("⚡ Writing a full memory dump — the game will freeze for a few seconds, and it needs free disk space about the size of your memory use.")
                .withStyle(ChatFormatting.GRAY));
        Lightning.recorder().heapDeep(keep,
                msg -> out.message(Component.literal("⚡ " + msg).withStyle(ChatFormatting.GRAY)),
                (line, file) -> out.message(Component.literal("⚡ Memory by mod: " + line + ". Full report: " + file).withStyle(ChatFormatting.GOLD)),
                error -> out.message(Component.literal("⚡ Memory analysis failed: " + error).withStyle(ChatFormatting.RED)));
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> profiler(BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        return Commands.literal("profiler")
                .then(Commands.literal("start")
                        .executes(c -> start(c, "", feedback))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .suggests(LightningCommands::suggestOptions)
                                .executes(c -> start(c, StringArgumentType.getString(c, "options"), feedback))))
                .then(Commands.literal("stop").executes(c -> stop(c, false, feedback)))
                .then(Commands.literal("cancel").executes(c -> stop(c, true, feedback)))
                .then(Commands.literal("status").executes(c -> status(c, feedback)))
                .then(Commands.literal("info").executes(c -> status(c, feedback)));
    }

    /** Shows the available options, with what each does, as the player types. */
    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestOptions(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int lastSpace = typed.lastIndexOf(' ');
        String word = typed.substring(lastSpace + 1);
        com.mojang.brigadier.suggestion.SuggestionsBuilder next = builder.createOffset(builder.getStart() + lastSpace + 1);
        for (CaptureOptions.Option o : CaptureOptions.suggest(typed)) {
            if (o.name().startsWith(word)) next.suggest(o.name(), Component.literal(o.description()));
        }
        return next.buildFuture();
    }

    private static int start(CommandContext<CommandSourceStack> c, String args, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        Feedback out = feedback.apply(c, true);
        CaptureOptions options;
        try {
            options = CaptureOptions.parse(args);
        } catch (IllegalArgumentException e) {
            out.message(Component.literal("⚡ " + e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        Recorder recorder = Lightning.recorder();
        boolean started = recorder.start(options, new Recorder.Listener() {
            @Override
            public void started(String message) {
                out.message(Component.literal(message).withStyle(ChatFormatting.GOLD));
            }

            @Override
            public void saved(Exporter.Result result) {
                out.saved(result);
            }

            @Override
            public void failed(String message) {
                out.message(Component.literal(message).withStyle(ChatFormatting.RED));
            }
        });
        if (!started) out.message(Component.literal("⚡ " + recorder.status()).withStyle(ChatFormatting.YELLOW));
        return started ? 1 : 0;
    }

    private static int stop(CommandContext<CommandSourceStack> c, boolean cancel, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        Recorder recorder = Lightning.recorder();
        if (!recorder.stop(cancel)) {
            feedback.apply(c, false).message(Component.literal("⚡ " + recorder.status()).withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        if (!cancel) feedback.apply(c, false).message(Component.literal("⚡ Saving…").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c, BiFunction<CommandContext<CommandSourceStack>, Boolean, Feedback> feedback) {
        feedback.apply(c, false).message(Component.literal("⚡ " + Lightning.recorder().status()).withStyle(ChatFormatting.GOLD));
        return 1;
    }
}
