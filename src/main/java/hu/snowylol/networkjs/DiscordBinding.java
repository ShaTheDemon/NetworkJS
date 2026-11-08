package hu.snowylol.networkjs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.util.Map;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class DiscordBinding {
    private JDA jda;
    private String token;
    private Map<String, String> channels;
    private boolean sanitizeMessages;
    private List<Consumer<DiscordMessage>> messageCallbacks;
    private volatile Consumer<DiscordButton> buttonCallback;
    
    public DiscordBinding(Map<String, Object> configMap) {
        this.token = (String) configMap.get("token");
        this.channels = (Map<String, String>) configMap.getOrDefault("channels", new HashMap<>());
        this.sanitizeMessages = (Boolean) configMap.getOrDefault("sanitizeMessages", true);
        this.messageCallbacks = new ArrayList<>();
        
        if (this.token == null) {
            throw new IllegalArgumentException("Discord token is required");
        }
        
        initialize();
    }
    
    private void initialize() {
        try {
            this.jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new MessageListener())
                    .build();
                    
            this.jda.awaitReady();
            NetworkJS.LOGGER.info("Discord bot connected successfully");
        } catch (Exception e) {
            NetworkJS.LOGGER.error("Failed to initialize Discord bot: " + e.getMessage());
            throw new RuntimeException("Discord bot initialization failed", e);
        }
    }
    
    public String sanitizeMessage(String message) {
        if (message == null) return "";
        String sanitized = message
            .replaceAll("@everyone", "@\u200Beveryone")     
            .replaceAll("@here", "@\u200Bhere")             
            .replaceAll("@&\\d+", "@\u200Brole")            
            .replaceAll("```", "\\`\\`\\`");
            
        Pattern userMentionPattern = Pattern.compile("<@!?(\\d+)>");
        Matcher matcher = userMentionPattern.matcher(sanitized);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String userId = matcher.group(1);
            String replacement = "@\u200Buser";
            try {
                var user = jda.getUserById(userId);
                if (user != null) {
                    replacement = "@\u200B" + user.getName();
                }
            } catch (Exception e) {}
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        sanitized = sb.toString();
        
        sanitized = sanitized.replaceAll("<#\\d+>", "#\u200Bchannel");
        return sanitized.substring(0, Math.min(sanitized.length(), 2000)); // do not use message. i was stupid! :D
    }
    
    public boolean sendMessage(String channelKey, String message) {
        try {
            String channelId = channels.get(channelKey);
            if (channelId == null) {
                NetworkJS.LOGGER.warn("Channel key '" + channelKey + "' not found in configuration");
                return false;
            }
            
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                NetworkJS.LOGGER.warn("Discord channel not found: " + channelId);
                return false;
            }
            
            String finalMessage = sanitizeMessages ? sanitizeMessage(message) : message;
            channel.sendMessage(finalMessage).queue();
            return true;
        } catch (Exception e) {
            NetworkJS.LOGGER.error("Failed to send Discord message: " + e.getMessage());
            return false;
        }
    }
    // i love embeds because they are almost the same as typescript lol (code wise)
    public boolean sendEmbed(String channelKey, Map<String, Object> embedData) {
        try {
            String channelId = channels.get(channelKey);
            if (channelId == null) {
                NetworkJS.LOGGER.warn("Channel key '" + channelKey + "' not found in configuration");
                return false;
            }
            
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                NetworkJS.LOGGER.warn("Discord channel not found: " + channelId);
                return false;
            }
            
            EmbedBuilder builder = new EmbedBuilder();
            
            if (embedData.containsKey("title")) {
                builder.setTitle((String) embedData.get("title"));
            }
            if (embedData.containsKey("description")) {
                String desc = (String) embedData.get("description");
                builder.setDescription(sanitizeMessages ? sanitizeMessage(desc) : desc);
            }
            if (embedData.containsKey("color")) {
                Object colorObj = embedData.get("color");
                if (colorObj instanceof Number) {
                    builder.setColor(((Number) colorObj).intValue());
                }
            }
            if (embedData.containsKey("footer")) {
                builder.setFooter((String) embedData.get("footer"));
            }
            
            channel.sendMessageEmbeds(builder.build()).queue();
            return true;
        } catch (Exception e) {
            NetworkJS.LOGGER.error("Failed to send Discord embed: " + e.getMessage());
            return false;
        }
    }
    
    MessageEmbed buildEmbed(Map<String, Object> embedData) {
        EmbedBuilder builder = new EmbedBuilder();
        if (embedData.containsKey("title")) {
            builder.setTitle(String.valueOf(embedData.get("title")));
        }
        if (embedData.containsKey("description")) {
            String desc = String.valueOf(embedData.get("description"));
            builder.setDescription(sanitizeMessages ? sanitizeMessage(desc) : desc);
        }
        if (embedData.get("color") instanceof Number) {
            builder.setColor(((Number) embedData.get("color")).intValue());
        }
        if (embedData.containsKey("footer")) {
            builder.setFooter(String.valueOf(embedData.get("footer")));
        }
        if (embedData.get("fields") instanceof List<?> fieldList) {
            for (Object item : fieldList) {
                if (item instanceof Map<?, ?> field) {
                    builder.addField(String.valueOf(field.get("name")), String.valueOf(field.get("value")), Boolean.TRUE.equals(field.get("inline")));
                }
            }
        }
        return builder.build();
    }

    static Button buildButton(Map<String, Object> data) {
        if (data == null || data.get("id") == null || data.get("label") == null) return null;
        String id = String.valueOf(data.get("id"));
        String label = String.valueOf(data.get("label"));
        String style = String.valueOf(data.getOrDefault("style", "primary")).toLowerCase();
        Button button;
        switch (style) {
            case "secondary":
                button = Button.secondary(id, label);
                break;
            case "success":
                button = Button.success(id, label);
                break;
            case "danger":
                button = Button.danger(id, label);
                break;
            default:
                button = Button.primary(id, label);
        }
        if (Boolean.TRUE.equals(data.get("disabled"))) {
            button = button.asDisabled();
        }
        return button;
    }

    static List<ActionRow> buildRows(List<Map<String, Object>> buttons) {
        List<ActionRow> rows = new ArrayList<>();
        if (buttons == null) return rows;
        List<Button> current = new ArrayList<>();
        for (Map<String, Object> data : buttons) {
            Button button = buildButton(data);
            if (button == null) continue;
            current.add(button);
            if (current.size() == 5) {
                rows.add(ActionRow.of(current));
                current = new ArrayList<>();
                if (rows.size() == 5) break;
            }
        }
        if (!current.isEmpty() && rows.size() < 5) {
            rows.add(ActionRow.of(current));
        }
        return rows;
    }

    public boolean sendEmbedWithButtons(String channelKey, Map<String, Object> embedData, List<Map<String, Object>> buttons) {
        try {
            String channelId = channels.get(channelKey);
            if (channelId == null) {
                NetworkJS.LOGGER.warn("Channel key '" + channelKey + "' not found in configuration");
                return false;
            }
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                NetworkJS.LOGGER.warn("Discord channel not found: " + channelId);
                return false;
            }
            channel.sendMessageEmbeds(buildEmbed(embedData)).setComponents(buildRows(buttons)).queue();
            return true;
        } catch (Exception e) {
            NetworkJS.LOGGER.error("Failed to send Discord embed with buttons: " + e.getMessage());
            return false;
        }
    }

    public void onButton(Consumer<DiscordButton> callback) {
        this.buttonCallback = callback;
    }

    public void setActivity(String activity) {
        try {
            jda.getPresence().setActivity(net.dv8tion.jda.api.entities.Activity.playing(activity));
        } catch (Exception e) {
            NetworkJS.LOGGER.error("Failed to set Discord activity: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
        }
    }
    
    public void onMessage(Consumer<DiscordMessage> callback) {
        this.messageCallbacks.add(callback);
    }
    
    public static class DiscordButton {
        private final DiscordBinding owner;
        private final ButtonInteractionEvent event;

        public DiscordButton(DiscordBinding owner, ButtonInteractionEvent event) {
            this.owner = owner;
            this.event = event;
        }

        public String getCustomId() { return event.getComponentId(); }
        public String getUserId() { return event.getUser().getId(); }
        public String getUserName() { return event.getUser().getName(); }
        public String getChannelId() { return event.getChannel().getId(); }
        public String getMessageId() { return event.getMessageId(); }
        public boolean isAcknowledged() { return event.isAcknowledged(); }

        public boolean deferEdit() {
            try {
                if (event.isAcknowledged()) return false;
                event.deferEdit().queue();
                return true;
            } catch (Exception e) {
                NetworkJS.LOGGER.error("Failed to defer Discord button: " + e.getMessage());
                return false;
            }
        }

        public boolean reply(String text) {
            try {
                String finalText = text == null ? "" : text;
                if (finalText.length() > 2000) finalText = finalText.substring(0, 2000);
                if (event.isAcknowledged()) {
                    event.getHook().sendMessage(finalText).setEphemeral(true).queue();
                } else {
                    event.reply(finalText).setEphemeral(true).queue();
                }
                return true;
            } catch (Exception e) {
                NetworkJS.LOGGER.error("Failed to reply to Discord button: " + e.getMessage());
                return false;
            }
        }

        public boolean editEmbed(Map<String, Object> embedData) {
            return editEmbed(embedData, null);
        }

        public boolean editEmbed(Map<String, Object> embedData, List<Map<String, Object>> buttons) {
            try {
                MessageEmbed embed = owner.buildEmbed(embedData);
                List<ActionRow> rows = buildRows(buttons);
                if (event.isAcknowledged()) {
                    event.getHook().editOriginalEmbeds(embed).setComponents(rows).queue();
                } else {
                    event.editMessageEmbeds(embed).setComponents(rows).queue();
                }
                return true;
            } catch (Exception e) {
                NetworkJS.LOGGER.error("Failed to edit Discord button message: " + e.getMessage());
                return false;
            }
        }
    }

    public static class DiscordMessage {
        private final String content;
        private final String author;
        private final String channelId;
        private final String channelName;
        private final boolean isFromConfiguredChannel;
        private final boolean isBot;
        private final Map<String, Object> user;
        
        public DiscordMessage(String content, String author, String channelId, String channelName, boolean isFromConfiguredChannel, boolean isBot, Map<String, Object> user) {
            this.content = content;
            this.author = author;
            this.channelId = channelId;
            this.channelName = channelName;
            this.isFromConfiguredChannel = isFromConfiguredChannel;
            this.isBot = isBot;
            this.user = user;
        }
        
        public String getContent() { return content; }
        public String getAuthor() { return author; }
        public String getChannelId() { return channelId; }
        public String getChannelName() { return channelName; }
        public boolean isFromConfiguredChannel() { return isFromConfiguredChannel; }
        public boolean isBot() { return isBot; }
        public Map<String, Object> getUser() { return user; }
    }
    
    private class MessageListener extends ListenerAdapter {
        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            Consumer<DiscordButton> handler = buttonCallback;
            if (handler == null) return;
            DiscordButton button = new DiscordButton(DiscordBinding.this, event);
            try {
                handler.accept(button);
            } catch (Exception e) {
                NetworkJS.LOGGER.error("Error in Discord button callback: " + e.getMessage());
                try {
                    if (!event.isAcknowledged()) {
                        event.reply("Co loi khi xu ly nut nay.").setEphemeral(true).queue();
                    }
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (messageCallbacks.isEmpty()) return;
            if (event.getAuthor().isBot()) return;
            
            String content = event.getMessage().getContentDisplay();
            String author = event.getAuthor().getName();
            String channelId = event.getChannel().getId();
            String channelName = event.getChannel().getName();
            boolean isBot = event.getAuthor().isBot();
            
            boolean isFromConfiguredChannel = channels.containsValue(channelId);
            
            Map<String, Object> user = new HashMap<>();
            user.put("id", event.getAuthor().getId());
            user.put("name", event.getAuthor().getName());
            user.put("displayName", event.getAuthor().getEffectiveName());
            user.put("discriminator", event.getAuthor().getDiscriminator());
            user.put("avatarUrl", event.getAuthor().getAvatarUrl());
            user.put("isBot", event.getAuthor().isBot());
            user.put("isSystem", event.getAuthor().isSystem());
            
            if (event.getMember() != null) {
                user.put("nickname", event.getMember().getNickname());
                user.put("colorRaw", event.getMember().getColorRaw());
                user.put("isOwner", event.getMember().isOwner());
                user.put("isPending", event.getMember().isPending());
                user.put("joinedAt", event.getMember().getTimeJoined().toString());
                user.put("boostedAt", event.getMember().getTimeBoosted() != null ? event.getMember().getTimeBoosted().toString() : null);
                
                List<String> roleNames = new ArrayList<>();
                List<String> roleIds = new ArrayList<>();
                String highestRole = null;
                String highestRoleId = null;
                
                if (!event.getMember().getRoles().isEmpty()) {
                    var roles = event.getMember().getRoles();
                    if (!roles.isEmpty()) {
                        highestRole = roles.get(0).getName();
                        highestRoleId = roles.get(0).getId();
                    }
                    for (var role : roles) {
                        roleNames.add(role.getName());
                        roleIds.add(role.getId());
                    }
                }
                
                user.put("roles", roleNames.toArray(new String[0]));
                user.put("roleIds", roleIds.toArray(new String[0]));
                user.put("highestRole", highestRole);
                user.put("highestRoleId", highestRoleId);
                user.put("roleCount", roleNames.size());
            } else {
                user.put("nickname", null);
                user.put("roles", new String[0]);
                user.put("roleIds", new String[0]);
                user.put("highestRole", null);
                user.put("highestRoleId", null);
                user.put("roleCount", 0);
            }
            
            DiscordMessage discordMessage = new DiscordMessage(content, author, channelId, channelName, isFromConfiguredChannel, isBot, user);
            
            for (Consumer<DiscordMessage> callback : messageCallbacks) {
                try {
                    callback.accept(discordMessage);
                } catch (Exception e) {
                    NetworkJS.LOGGER.error("Error in Discord message callback: " + e.getMessage());
                }
            }
        }
    }
}
