package org.evocraft.evoreferral;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.evocraft.evocore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class ReferralManager {

    public static void checkAndReward(Player playerEntity) {
        if (!(playerEntity instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) playerEntity;
        String username = player.getGameProfile().getName();

        new Thread(() -> {
            try (Connection conn = DatabaseManager.get().getConnection()) {

                // Aflăm ID-ul jucătorului curent (pentru a vedea dacă el a adus pe alții)
                int myId = -1;
                try (PreparedStatement idStmt = conn.prepareStatement("SELECT id FROM players WHERE username = ?")) {
                    idStmt.setString(1, username);
                    ResultSet idRs = idStmt.executeQuery();
                    if (idRs.next()) myId = idRs.getInt("id");
                }

                // =========================================================================
                // PARTEA 1: SUNT JUCĂTOR NOU ȘI AM FOLOSIT UN COD?
                // =========================================================================
                String checkQuery = "SELECT referrer_id, newbie_reward_claimed, referrer_reward_claimed FROM player_referrals WHERE username = ?";
                try (PreparedStatement stmt = conn.prepareStatement(checkQuery)) {
                    stmt.setString(1, username);
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        int referrerId = rs.getInt("referrer_id");
                        int newbieClaimed = rs.getInt("newbie_reward_claimed");
                        int referrerClaimed = rs.getInt("referrer_reward_claimed");

                        // Recompensă de Bază (1000 Lei) pentru MINE (Noul jucător)
                        if (newbieClaimed == 0) {
                            player.getServer().execute(() -> {
                                player.getServer().getCommands().performPrefixedCommand(player.getServer().createCommandSourceStack(), "bani give " + username + " 1000");
                                player.sendSystemMessage(Component.literal("§a[Evo] Bine ai venit! Ai primit §e1,000 Lei§a pentru că ai folosit un cod de invitație!"));
                            });
                            try (PreparedStatement updateNewbie = conn.prepareStatement("UPDATE player_referrals SET newbie_reward_claimed = 1 WHERE username = ?")) {
                                updateNewbie.setString(1, username);
                                updateNewbie.executeUpdate();
                            }
                        }

                        // Îi dăm premiul (5000 Lei) celui care m-a invitat DACĂ ESTE ONLINE ACUM
                        if (referrerId != -1 && referrerClaimed == 0) {
                            String getRefData = "SELECT username FROM players WHERE id = ?";
                            String referrerName = null;
                            try (PreparedStatement refStmt = conn.prepareStatement(getRefData)) {
                                refStmt.setInt(1, referrerId);
                                ResultSet refRs = refStmt.executeQuery();
                                if (refRs.next()) referrerName = refRs.getString("username");
                            }

                            if (referrerName != null) {
                                ServerPlayer refOnline = player.getServer().getPlayerList().getPlayerByName(referrerName);

                                if (refOnline != null) {
                                    // Este ONLINE! Îi dăm banii instant.
                                    final String finalRefName = referrerName;
                                    player.getServer().execute(() -> {
                                        player.getServer().getCommands().performPrefixedCommand(player.getServer().createCommandSourceStack(), "bani give " + finalRefName + " 5000");
                                        refOnline.sendSystemMessage(Component.literal("§a[Evo] Jucătorul §f" + username + " §aa intrat folosind codul tău! Ai primit §e5,000 Lei§a."));
                                    });

                                    try (PreparedStatement updateRef = conn.prepareStatement("UPDATE player_referrals SET referrer_reward_claimed = 1 WHERE username = ?")) {
                                        updateRef.setString(1, username);
                                        updateRef.executeUpdate();
                                    }

                                    // Verificăm dacă a atins un milestone (5, 10, 25)
                                    checkMilestones(conn, player, referrerId, finalRefName);
                                }
                                // ATENȚIE: Dacă e offline, NU facem update. Rămâne "referrer_reward_claimed = 0"
                                // pentru a-i da banii când se va conecta el!
                            }
                        }
                    }
                }

                // =========================================================================
                // PARTEA 2: SUNT REFERRAL PENTRU ALȚII ȘI AM BANI NEPRELUAȚI (OFFLINE EARNINGS)?
                // =========================================================================
                if (myId != -1) {
                    List<String> pendingNewbies = new ArrayList<>();
                    // Căutăm jucătorii care au intrat (newbie = 1) dar pentru care eu nu am luat banii (referrer = 0)
                    String pendingQuery = "SELECT username FROM player_referrals WHERE referrer_id = ? AND referrer_reward_claimed = 0 AND newbie_reward_claimed = 1";

                    try (PreparedStatement pendStmt = conn.prepareStatement(pendingQuery)) {
                        pendStmt.setInt(1, myId);
                        ResultSet pendRs = pendStmt.executeQuery();
                        while (pendRs.next()) {
                            pendingNewbies.add(pendRs.getString("username"));
                        }
                    }

                    // Dacă s-au adunat jucători în timp ce eram offline
                    if (!pendingNewbies.isEmpty()) {
                        int totalOwed = pendingNewbies.size() * 5000;

                        player.getServer().execute(() -> {
                            player.sendSystemMessage(Component.literal("§a[Evo] În timp ce erai offline, §e" + pendingNewbies.size() + " jucători §aau intrat cu codul tău!"));
                            player.sendSystemMessage(Component.literal("§a[Evo] Ai primit automat suma restanțelor: §e" + totalOwed + " Lei§a!"));
                        });

                        // Procesăm fiecare plată restantă pe rând, pentru a verifica milestone-urile corect
                        for (String newbieUser : pendingNewbies) {
                            player.getServer().execute(() -> {
                                player.getServer().getCommands().performPrefixedCommand(player.getServer().createCommandSourceStack(), "bani give " + username + " 5000");
                            });

                            try (PreparedStatement updatePend = conn.prepareStatement("UPDATE player_referrals SET referrer_reward_claimed = 1 WHERE username = ?")) {
                                updatePend.setString(1, newbieUser);
                                updatePend.executeUpdate();
                            }

                            // Verificăm dacă prin adăugarea acestui jucător a atins pragul de 5, 10 sau 25
                            checkMilestones(conn, player, myId, username);
                        }
                    }
                }

            } catch (Exception e) {
                System.out.println("[EvoReferral] EROARE DATABASE: " + e.getMessage());
            }
        }).start();
    }

    private static void checkMilestones(Connection conn, ServerPlayer serverPlayer, int referrerId, String referrerName) {
        try {
            int totalInvites = 0;
            // Numărăm DOAR invitațiile pentru care s-a confirmat intrarea și plata
            String countInvites = "SELECT COUNT(*) FROM player_referrals WHERE referrer_id = ? AND referrer_reward_claimed = 1";
            try (PreparedStatement countStmt = conn.prepareStatement(countInvites)) {
                countStmt.setInt(1, referrerId);
                ResultSet countRs = countStmt.executeQuery();
                if (countRs.next()) totalInvites = countRs.getInt(1);
            }

            int bonusAmount = 0;
            String milestoneText = "";

            // Pragurile bonus
            if (totalInvites == 5) {
                bonusAmount = 10000;
                milestoneText = "§6§lPREMIU: 5 INVITE-URI ATINSE!";
            } else if (totalInvites == 10) {
                bonusAmount = 25000;
                milestoneText = "§6§lPREMIU: 10 INVITE-URI ATINSE!";
            } else if (totalInvites == 25) {
                bonusAmount = 50000;
                milestoneText = "§b§lPREMIU LEGENDAR: 25 INVITE-URI ATINSE!";
            }

            // Oferim bonusul doar exact la atingerea pragului
            if (bonusAmount > 0) {
                final int finalBonus = bonusAmount;
                final String finalMsg = milestoneText;
                final int finalTotalInvites = totalInvites;

                serverPlayer.getServer().execute(() -> {
                    serverPlayer.getServer().getCommands().performPrefixedCommand(serverPlayer.getServer().createCommandSourceStack(), "bani give " + referrerName + " " + finalBonus);

                    serverPlayer.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal("§8[§aEvoReferral§8] §fJucătorul §a" + referrerName + " §fa atins pragul de §e" + finalTotalInvites + " §finvite-uri și a primit un bonus de §6" + finalBonus + " Lei§f!"),
                            false
                    );

                    ServerPlayer refOnline = serverPlayer.getServer().getPlayerList().getPlayerByName(referrerName);
                    if (refOnline != null) {
                        refOnline.sendSystemMessage(Component.literal("§6§l" + finalMsg + " §aAi primit un bonus extra de §e" + finalBonus + " Lei§a!"));
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}