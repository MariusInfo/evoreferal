package org.evocraft.evoreferral;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.evocraft.evocore.data.EconomyManager;
import org.evocraft.evocore.database.DatabaseManager;
import org.evocraft.evocore.util.EvoCurrencyFormatter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReferralManager {

    private static volatile ExecutorService dbExecutor = createExecutor();

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EvoReferral DB Worker");
            t.setDaemon(true);
            return t;
        });
    }

    private static synchronized ExecutorService getExecutor() {
        if (dbExecutor == null || dbExecutor.isShutdown() || dbExecutor.isTerminated()) {
            dbExecutor = createExecutor();
        }
        return dbExecutor;
    }

    public static synchronized void shutdown() {
        ExecutorService exec = dbExecutor;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
    }

    private static boolean tableExists(Connection conn, String tableName) {
        if (conn == null || tableName == null) return false;
        try {
            if (DatabaseManager.get().isSqlite()) {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                    stmt.setString(1, tableName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next();
                    }
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SHOW TABLES LIKE ?")) {
                    stmt.setString(1, tableName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void checkAndReward(Player playerEntity) {
        if (!(playerEntity instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        UUID playerUuid = player.getUUID();
        String username = player.getGameProfile().getName();

        getExecutor().execute(() -> {
            int myId = -1;
            int referrerId = -1;
            int newbieClaimed = -1;
            int referrerClaimed = -1;
            String referrerName = null;
            boolean newbieJustClaimed = false;
            List<String> claimedPendingNewbies = new ArrayList<>();
            List<MilestoneAward> pendingMilestones = new ArrayList<>();

            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    if (conn == null || conn.isClosed()) return;
                    if (!tableExists(conn, "players") || !tableExists(conn, "player_referrals")) return;

                    // Aflăm ID-ul jucătorului curent (pentru a vedea dacă el a adus pe alții)
                    try (PreparedStatement idStmt = conn.prepareStatement("SELECT id FROM players WHERE username = ?")) {
                        idStmt.setString(1, username);
                        try (ResultSet idRs = idStmt.executeQuery()) {
                            if (idRs.next()) myId = idRs.getInt("id");
                        }
                    }

                    // PARTEA 1: Jucătorul curent a folosit un cod de invitație?
                    String checkQuery = "SELECT referrer_id, newbie_reward_claimed, referrer_reward_claimed FROM player_referrals WHERE username = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(checkQuery)) {
                        stmt.setString(1, username);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                referrerId = rs.getInt("referrer_id");
                                newbieClaimed = rs.getInt("newbie_reward_claimed");
                                referrerClaimed = rs.getInt("referrer_reward_claimed");
                            }
                        }
                    }

                    // Claim atomic înainte de plată pentru a preveni dublarea la relog rapid
                    if (newbieClaimed == 0) {
                        try (PreparedStatement updateNewbie = conn.prepareStatement(
                                "UPDATE player_referrals SET newbie_reward_claimed = 1 WHERE username = ? AND newbie_reward_claimed = 0")) {
                            updateNewbie.setString(1, username);
                            if (updateNewbie.executeUpdate() > 0) {
                                newbieJustClaimed = true;
                            }
                        }
                    }

                    if (referrerId != -1 && referrerClaimed == 0) {
                        try (PreparedStatement refStmt = conn.prepareStatement("SELECT username FROM players WHERE id = ?")) {
                            refStmt.setInt(1, referrerId);
                            try (ResultSet refRs = refStmt.executeQuery()) {
                                if (refRs.next()) referrerName = refRs.getString("username");
                            }
                        }
                    }

                    // PARTEA 2: Recompense restante acumulate cât timp jucătorul a fost offline
                    if (myId != -1) {
                        List<String> pendingNewbies = new ArrayList<>();
                        String pendingQuery = "SELECT username FROM player_referrals WHERE referrer_id = ? AND referrer_reward_claimed = 0 AND newbie_reward_claimed = 1";
                        try (PreparedStatement pendStmt = conn.prepareStatement(pendingQuery)) {
                            pendStmt.setInt(1, myId);
                            try (ResultSet pendRs = pendStmt.executeQuery()) {
                                while (pendRs.next()) {
                                    pendingNewbies.add(pendRs.getString("username"));
                                }
                            }
                        }

                        for (String newbieUser : pendingNewbies) {
                            try (PreparedStatement updatePend = conn.prepareStatement(
                                    "UPDATE player_referrals SET referrer_reward_claimed = 1 WHERE username = ? AND referrer_reward_claimed = 0")) {
                                updatePend.setString(1, newbieUser);
                                if (updatePend.executeUpdate() > 0) {
                                    claimedPendingNewbies.add(newbieUser);
                                    MilestoneAward award = checkMilestones(conn, myId);
                                    if (award != null) pendingMilestones.add(award);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[EvoReferral] Database error: " + e.getMessage());
                    return;
                }
            }

            final boolean giveNewbie = newbieJustClaimed;
            final String finalReferrerName = referrerName;
            final int finalReferrerId = referrerId;

            server.execute(() -> {
                ServerPlayer livePlayer = server.getPlayerList().getPlayer(playerUuid);
                if (livePlayer != null) {
                    if (giveNewbie) {
                        EconomyManager.get().addBalance(playerUuid, 1000.0);
                        livePlayer.sendSystemMessage(Component.literal(
                                "§a[EvoReferral] Welcome! You received §e" + EvoCurrencyFormatter.formatWithCurrency(1000.0) + " §afor using a referral code!"));
                    }

                    if (!claimedPendingNewbies.isEmpty()) {
                        double totalOwed = claimedPendingNewbies.size() * 5000.0;
                        EconomyManager.get().addBalance(playerUuid, totalOwed);
                        livePlayer.sendSystemMessage(Component.literal(
                                "§a[EvoReferral] While you were offline, §e" + claimedPendingNewbies.size() + " player(s) §ajoined using your code!"));
                        livePlayer.sendSystemMessage(Component.literal(
                                "§a[EvoReferral] You received: §e" + EvoCurrencyFormatter.formatWithCurrency(totalOwed) + "§a!"));

                        for (MilestoneAward award : pendingMilestones) {
                            awardMilestone(server, playerUuid, username, award);
                        }
                    }
                }

                // Dacă referrer-ul este online acum și plata nu a fost revendicată, procesăm atomic
                if (finalReferrerName != null && finalReferrerId != -1) {
                    ServerPlayer refOnline = server.getPlayerList().getPlayerByName(finalReferrerName);
                    if (refOnline != null) {
                        UUID refUuid = refOnline.getUUID();
                        String refProfileName = refOnline.getGameProfile().getName();
                        payOnlineReferrerAsync(server, username, finalReferrerId, refUuid, refProfileName);
                    }
                }
            });
        });
    }

    private static void payOnlineReferrerAsync(MinecraftServer server, String newbieUsername, int referrerId, UUID refUuid, String refName) {
        getExecutor().execute(() -> {
            boolean claimedNow = false;
            MilestoneAward award = null;

            synchronized (DatabaseManager.get()) {
                try {
                    Connection conn = DatabaseManager.get().getConnection();
                    if (conn == null || conn.isClosed()) return;

                    try (PreparedStatement updateRef = conn.prepareStatement(
                            "UPDATE player_referrals SET referrer_reward_claimed = 1 WHERE username = ? AND referrer_reward_claimed = 0")) {
                        updateRef.setString(1, newbieUsername);
                        if (updateRef.executeUpdate() > 0) {
                            claimedNow = true;
                            award = checkMilestones(conn, referrerId);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[EvoReferral] Database error paying referrer: " + e.getMessage());
                    return;
                }
            }

            if (!claimedNow) return;
            final MilestoneAward finalAward = award;

            server.execute(() -> {
                EconomyManager.get().addBalance(refUuid, 5000.0);
                ServerPlayer refOnline = server.getPlayerList().getPlayer(refUuid);
                if (refOnline != null) {
                    refOnline.sendSystemMessage(Component.literal(
                            "§a[EvoReferral] Player §f" + newbieUsername + " §ajoined using your code! You received §e" + EvoCurrencyFormatter.formatWithCurrency(5000.0) + "§a."));
                }
                if (finalAward != null) {
                    awardMilestone(server, refUuid, refName, finalAward);
                }
            });
        });
    }

    private record MilestoneAward(int totalInvites, double bonusAmount, String milestoneTitle) {}

    private static MilestoneAward checkMilestones(Connection conn, int referrerId) {
        try {
            int totalInvites = 0;
            String countInvites = "SELECT COUNT(*) FROM player_referrals WHERE referrer_id = ? AND referrer_reward_claimed = 1";
            try (PreparedStatement countStmt = conn.prepareStatement(countInvites)) {
                countStmt.setInt(1, referrerId);
                try (ResultSet countRs = countStmt.executeQuery()) {
                    if (countRs.next()) totalInvites = countRs.getInt(1);
                }
            }

            if (totalInvites == 5) {
                return new MilestoneAward(5, 10000.0, "§6§lMILESTONE: 5 INVITES REACHED!");
            } else if (totalInvites == 10) {
                return new MilestoneAward(10, 25000.0, "§6§lMILESTONE: 10 INVITES REACHED!");
            } else if (totalInvites == 25) {
                return new MilestoneAward(25, 50000.0, "§b§lLEGENDARY MILESTONE: 25 INVITES REACHED!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void awardMilestone(MinecraftServer server, UUID refUuid, String refName, MilestoneAward award) {
        EconomyManager.get().addBalance(refUuid, award.bonusAmount());
        String formattedBonus = EvoCurrencyFormatter.formatWithCurrency(award.bonusAmount());

        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§8[§aEvoReferral§8] §fPlayer §a" + refName + " §freached §e" + award.totalInvites()
                        + " §finvites and received a bonus of §6" + formattedBonus + "§f!"),
                false
        );

        ServerPlayer refOnline = server.getPlayerList().getPlayer(refUuid);
        if (refOnline != null) {
            refOnline.sendSystemMessage(Component.literal(
                    award.milestoneTitle() + " §aYou received an extra bonus of §e" + formattedBonus + "§a!"));
        }
    }
}