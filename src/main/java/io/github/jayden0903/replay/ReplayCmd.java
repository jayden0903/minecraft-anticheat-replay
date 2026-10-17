package io.github.jayden0903.replay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /replay (alias /리플레이; staff) and rewindreplay (console / other plugins, e.g. an anticheat's punishment command):
 *   rewindreplay mark <player> <reason...>         - freeze the last 60 s + next 30 s of a player
 *   /replay mark|기록 <player> [reason]            - same, as the staff member who typed it
 *   /replay list|목록 [player] | view|보기 <id> | summary|요약 <id> | stop|종료 | status|상태
 * Sub-commands are accepted in English and Korean under every label. Chat buttons run the Korean alias: it is unlikely to
 * be taken by another plugin (several replay plugins register /replay), and it is the path that was tested in game.
 */
final class ReplayCmd {
    static final String PERM = "rewindreplay.admin";
    /** Prefix of every chat-button command. */
    static final String CLICK = "/리플레이 ";
    private static final Set<String> CONTROLS = Set.of("pause", "back10", "back1", "fwd1", "fwd10", "prev", "next", "mark", "restart", "s025", "s05", "s1", "s2", "s4", "pov", "goto", "summary", "controls", "stop");

    static boolean handle(RewindReplay pl, CommandSender sender, String cmd, String[] a) {
        if (!sender.hasPermission(PERM)) { sender.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED)); return true; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        String[] rest = a.length == 0 ? a : Arrays.copyOfRange(a, 1, a.length);
        switch (sub) {
            case "mark", "기록" -> {
                if (rest.length < 1) { sender.sendMessage(Component.text("사용법: /리플레이 기록 <닉네임> [사유]", NamedTextColor.GRAY)); return true; }
                String reason = String.join(" ", Arrays.copyOfRange(rest, 1, rest.length));
                boolean manual = sender instanceof Player;
                String source = manual ? "op:" + sender.getName() : "console";
                String msg = pl.service().mark(rest[0], reason, source, manual);
                sender.sendMessage(Component.text("[리플레이] " + msg, ReplaySession.SOFT));
            }
            case "list", "목록" -> list(pl, sender, rest.length > 0 ? rest[0] : null);
            case "view", "보기" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("게임 안에서만 볼 수 있습니다."); return true; }
                if (rest.length < 1) { sender.sendMessage(Component.text("사용법: /리플레이 보기 <클립 번호>", NamedTextColor.GRAY)); return true; }
                pl.startView(p, rest[0]);
            }
            case "summary", "요약" -> {
                if (rest.length < 1) { sender.sendMessage(Component.text("사용법: /리플레이 요약 <클립 번호>", NamedTextColor.GRAY)); return true; }
                ClipService.IndexEntry e = pl.service().entry(rest[0]);
                if (e == null) { sender.sendMessage(Component.text("클립을 찾을 수 없습니다: " + rest[0], NamedTextColor.RED)); return true; }
                pl.service().io.execute(() -> {
                    try {
                        Clip c = Clip.read(pl.service().file(e));
                        if (sender instanceof Player p) p.getScheduler().run(pl, t -> pl.printSummary(p, c), null); else pl.printSummary(sender, c);
                    } catch (Exception ex) { sender.sendMessage("클립을 읽지 못했습니다: " + ex.getMessage()); }
                });
            }
            case "control", "제어" -> {
                if (!(sender instanceof Player p)) return true;
                ReplaySession s = pl.session(p);
                if (s == null) { p.sendMessage(Component.text("보고 있는 리플레이가 없습니다.", NamedTextColor.GRAY)); return true; }
                if (rest.length > 0 && CONTROLS.contains(rest[0])) s.actions.add(rest[0]);
                // "at <seconds>": jump to that clip time and pause (exact frame review)
                else if (rest.length > 1 && rest[0].equals("at")) {
                    try { double sec = Double.parseDouble(rest[1]); if (sec >= 0 && sec < 86400) s.actions.add("at:" + sec); } catch (NumberFormatException ignored) { }
                }
            }
            case "stop", "종료" -> {
                if (!(sender instanceof Player p)) return true;
                ReplaySession s = pl.session(p);
                if (s == null) p.sendMessage(Component.text("보고 있는 리플레이가 없습니다.", NamedTextColor.GRAY)); else s.actions.add("stop");
            }
            case "status", "상태" -> { for (String l : pl.status()) sender.sendMessage(Component.text(l, ReplaySession.SOFT)); }
            case "resetstats" -> { pl.resetStats(); sender.sendMessage("통계를 초기화했습니다."); }
            default -> help(sender);
        }
        return true;
    }

    static void help(CommandSender s) {
        s.sendMessage(Component.text("[리플레이] 의심 플레이어의 최근 이동과 시선을 저장하고 다시 봅니다 (관리자 전용)", ReplaySession.ACCENT));
        for (String l : new String[] {
            "/리플레이 기록 <닉네임> [사유] - 앞 60초와 뒤 30초를 클립으로 저장",
            "/리플레이 목록 [닉네임] - 최근 클립", "/리플레이 보기 <번호> - 게임 안에서 재생 (본인에게만 보임)",
            "/리플레이 요약 <번호> - 수치 요약", "/리플레이 종료 - 재생을 끝내고 원래 자리로", "/리플레이 상태 - 메모리·CPU·저장 상태",
            "영문 명령: /replay mark | list | view | summary | stop | status",
            "콘솔/다른 플러그인: rewindreplay mark <닉네임> <사유>"}) s.sendMessage(Component.text(l, NamedTextColor.GRAY));
    }

    static void list(RewindReplay pl, CommandSender s, String who) {
        List<ClipService.IndexEntry> l = pl.service().list(who, 10);
        if (l.isEmpty()) { s.sendMessage(Component.text("저장된 클립이 없습니다" + (who == null ? "." : ": " + who), NamedTextColor.GRAY)); return; }
        s.sendMessage(Component.text("[리플레이] 최근 클립" + (who == null ? "" : " · " + who) + " (최신 순)", ReplaySession.ACCENT));
        for (ClipService.IndexEntry e : l) {
            TextComponent.Builder b = Component.text();
            b.append(Component.text(RewindReplay.time(e.createdAtMs()) + " ", NamedTextColor.GRAY))
                .append(Component.text(e.suspect(), NamedTextColor.WHITE))
                .append(Component.text(" · " + shorten(e.reasons(), 40) + " ", ReplaySession.SOFT))
                .append(view(e.id())).append(Component.space()).append(sum(e.id()));
            s.sendMessage(b.build().hoverEvent(HoverEvent.showText(Component.text(e.id() + "\n" + e.headline() + "\n출처: " + e.sources()))));
        }
    }

    static Component view(String id) {
        return Component.text("[보기]", TextColor.color(0xE8C07D)).clickEvent(ClickEvent.runCommand(CLICK + "view " + id)).hoverEvent(HoverEvent.showText(Component.text(id + " 재생")));
    }

    static Component sum(String id) {
        return Component.text("[요약]", TextColor.color(0xB8DDA8)).clickEvent(ClickEvent.runCommand(CLICK + "summary " + id)).hoverEvent(HoverEvent.showText(Component.text(id + " 수치 요약")));
    }

    private static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    static List<String> complete(RewindReplay pl, CommandSender sender, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(PERM)) return out;
        boolean ko = alias.endsWith("리플레이");
        if (a.length == 1) {
            for (String s : ko ? new String[] {"기록", "목록", "보기", "요약", "종료", "상태"} : new String[] {"mark", "list", "view", "summary", "stop", "status"})
                if (s.startsWith(a[0])) out.add(s);
        } else if (a.length == 2) {
            String sub = a[0];
            if (sub.equals("기록") || sub.equals("mark") || sub.equals("목록") || sub.equals("list")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            } else if (sub.equals("보기") || sub.equals("view") || sub.equals("요약") || sub.equals("summary")) {
                for (ClipService.IndexEntry e : pl.service().list(null, 30)) if (e.id().startsWith(a[1])) out.add(e.id());
            }
        }
        return out;
    }
}
