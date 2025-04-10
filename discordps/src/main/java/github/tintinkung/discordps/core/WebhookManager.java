package github.tintinkung.discordps.core;

import github.scarsz.discordsrv.dependencies.jda.api.AccountType;
import github.scarsz.discordsrv.dependencies.jda.api.exceptions.AccountTypeException;
import github.scarsz.discordsrv.dependencies.jda.api.exceptions.ParsingException;
import github.scarsz.discordsrv.dependencies.jda.api.requests.RestAction;
import github.scarsz.discordsrv.dependencies.jda.api.utils.data.DataArray;
import github.scarsz.discordsrv.dependencies.jda.api.utils.data.DataObject;
import github.scarsz.discordsrv.dependencies.jda.internal.JDAImpl;
import github.scarsz.discordsrv.dependencies.jda.internal.requests.RestActionImpl;
import github.scarsz.discordsrv.dependencies.jda.internal.requests.Route;
import github.scarsz.discordsrv.dependencies.jda.internal.utils.Checks;
import github.scarsz.discordsrv.dependencies.okhttp3.*;
import github.tintinkung.discordps.ConfigPaths;
import github.tintinkung.discordps.DiscordPS;
import github.scarsz.discordsrv.Debug;
import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.commons.lang3.StringUtils;
import github.scarsz.discordsrv.dependencies.jda.api.Permission;
import github.scarsz.discordsrv.dependencies.jda.api.entities.*;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.ActionRow;
import github.scarsz.discordsrv.dependencies.jda.api.requests.restaction.MessageAction;
import github.scarsz.discordsrv.dependencies.jda.internal.utils.BufferedRequestBody;
import github.scarsz.discordsrv.dependencies.json.JSONArray;
import github.scarsz.discordsrv.dependencies.json.JSONObject;
import github.scarsz.discordsrv.dependencies.okio.Okio;
import github.scarsz.discordsrv.util.*;
import github.tintinkung.discordps.core.utils.AvailableTags;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Modified version of
 * <a href="https://github.com/DiscordSRV/DiscordSRV/blob/9d4734818ab27069d76f264a4cda74a699806770/src/main/java/github/scarsz/discordsrv/util/WebhookUtil.java">
 *     github.scarsz.discordsrv.util.WebhookUtil
 * </a>
 */
public class WebhookManager {
    private static boolean isValidWebhook = true;
    private static boolean loggedBannedWords = false;
    private static final String channelID;
    private static final String webhookID;
    private static final JDAImpl jda;
    private static Webhook webhook;

    static {
        FileConfiguration configFile = DiscordPS.getPlugin().getConfig();
        channelID = configFile.getString(ConfigPaths.WEBHOOK_CHANNEL_ID);
        webhookID = configFile.getString(ConfigPaths.WEBHOOK_ID);
        jda = (JDAImpl) DiscordSRV.getPlugin().getJda();

        try {
            AccountTypeException.check(jda.getAccountType(), AccountType.BOT);
        }
        catch (AccountTypeException ex) {
            isValidWebhook = false;
            DiscordPS.error("Configured Discord BOT is not suitable for this plugin", ex);
        }

        // Parse webhook reference from config
        try {
            if(channelID != null) Checks.isSnowflake(channelID, "Webhook Channel ID");
            else DiscordPS.error("Configured Channel ID is invalid, please check the config file");

            if(webhookID != null) Checks.isSnowflake(webhookID, "Webhook ID");
            else DiscordPS.error("Configured Webhook ID is invalid, please check the config file");
        }
        catch (IllegalArgumentException ex) {
            isValidWebhook = false;
            DiscordPS.error(ex);
        }

        // Fetch database to update webhooks every day
        try {
            // get rid of all previous webhooks created by DiscordSRV if they don't match a good channel
            for (Guild guild : DiscordSRV.getPlugin().getJda().getGuilds()) {
                Member selfMember = guild.getSelfMember();
                if (!selfMember.hasPermission(Permission.MANAGE_WEBHOOKS)) {
                    DiscordSRV.error("Unable to manage webhooks guild-wide in " + guild + ", please allow the permission MANAGE_WEBHOOKS");
                    continue;
                }

                guild.retrieveWebhooks().queue(webhooks -> {
                    for (Webhook webhook : webhooks) {
                        Member owner = webhook.getOwner();
                        if (owner == null || !owner.getId().equals(selfMember.getId()) || !webhook.getName().startsWith("DiscordSRV")) {
                            continue;
                        }

//                        if (DiscordSRV.getPlugin().getDestinationGameChannelNameForTextChannel(webhook.getChannel()) == null) {
//                            webhook.delete().reason("DiscordSRV: Purging webhook for unlinked channel").queue();
//                        }
                    }
                });
            }
        } catch (Exception e) {
            DiscordPS.warning("Failed to purge already existing webhooks: " + e.getMessage());
        }

        // Initialize webhook reference
        if(channelID != null && webhookID != null) {
            try {
                webhook = new Webhook.WebhookReference(
                    jda,
                    Long.parseUnsignedLong(webhookID),
                    Long.parseUnsignedLong(channelID))
                .resolve().complete();
            }
            catch (Error unknownError) {
                DiscordPS.error("Failed to resolve webhook reference of unknown error: ", unknownError);
                isValidWebhook = false;
            }
        }
        else {
            DiscordPS.error("Failed to configure webhook setting by " + WebhookManager.class.getName());
            isValidWebhook = false;
        }

        // Resolve the config path into tag enum
        try {
            AvailableTags.resolveAllTag(configFile);
        }
        catch (NoSuchElementException ex) {
            DiscordPS.error("Failed to resolve available tag from config file.", ex);
            isValidWebhook = false;
        }
    }

    public static void validateWebhook() throws RuntimeException {
        if(!isValidWebhook) throw new RuntimeException("WebhookManager is not configured properly");
    }

    public static void validateStatusTags() throws RuntimeException {
        validateWebhook();

        DataArray availableTags = getAvailableTags(true)
            .complete()
            .orElseThrow(() -> new RuntimeException("Failed to fetch webhook channel data to validate available tags."));
        try {
            AvailableTags.initCache(availableTags);
            AvailableTags.applyAllTag();
        }
        catch (ParsingException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String getWebhookID() {
        return webhookID;
    }

    public static String getChannelID() {
        return channelID;
    }

    public static Guild getGuild() {
        return webhook.getGuild();
    }

    public static RestAction<Optional<DataArray>> getAvailableTags(boolean allowSecondAttempt) {
        Route.CompiledRoute route = Route.get(Route.Channels.MODIFY_CHANNEL.getRoute()).compile(channelID);

        return new RestActionImpl<>(jda, route, (response, request) -> {
            try {
                int status = response.code;
                if (status == 404) {
                    // 404 = Invalid Webhook (most likely to have been deleted)
                    DiscordPS.error("Channel GET returned 404" + (allowSecondAttempt? " ... retrying in 5 seconds" : ""));
                    if (allowSecondAttempt)
                        return getAvailableTags(false).completeAfter(5, TimeUnit.SECONDS);
                    request.cancel();

                    return Optional.empty();
                }
                Optional<DataObject> body = response.optObject();

                DiscordPS.info("Got API response: " + response.getString());

                if (body.isPresent()) {
                    if (body.get().hasKey("code")) {
                        if (body.get().getInt("code") == 10015) {
                            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 10015 (Unknown Webhook)");
                            request.cancel();
                            return Optional.empty();
                        }
                    }

                    DiscordPS.info("Packaging API response of: " + body.get().toPrettyString());

                    if(body.get().hasKey("available_tags"))
                        return Optional.of(body.get().getArray("available_tags"));

                    return Optional.empty();
                }
            }
            catch (Throwable ex) {
                DiscordPS.info("Failed to receive API response: " + ex.toString());
            }
            request.cancel();
            return Optional.empty();
        });
    }

    public static RestAction<Optional<MessageReference>> newThreadFromWebhook(String threadName, String avatarURL, String message, MessageEmbed embed) throws NoSuchElementException {
        return executeWebhook(webhook, threadName, avatarURL, null, message, Collections.singletonList(embed), null, null, true).orElseThrow();
    }

    public static RestAction<Optional<MessageReference>> newThreadFromWebhook(String threadName, String avatarURL, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends ActionRow> interactions) throws NoSuchElementException {
        return executeWebhook(webhook, threadName, avatarURL, null, message, Collections.singletonList(embed), attachments, interactions, false).orElseThrow();
    }

    public static Optional<RestAction<Optional<MessageReference>>> editWebhookMessage(String messageID, String message, MessageEmbed embed, Map<String, InputStream> attachments, Collection<? extends ActionRow> interactions) {
        return executeWebhook(webhook, null, null, messageID, message, Collections.singletonList(embed), attachments, interactions, false);
    }

    public static Optional<RestAction<Optional<MessageReference>>> editWebhookMessage(String messageID, String message, MessageEmbed embed) {
        DiscordPS.info("Editing message ID: " + messageID);
        return executeWebhook(webhook, null, null, messageID, message, Collections.singletonList(embed), null, null, false);
    }

    private static Optional<RestAction<Optional<MessageReference>>> executeWebhook(
            Webhook webhook,
            String threadName,
            String webhookAvatarUrl,
            String editMessageID,
            String message,
            Collection<? extends MessageEmbed> embeds,
            Map<String, InputStream> attachments,
            Collection<? extends ActionRow> interactions,
            boolean allowSecondAttempt) {

        // Validate
        if (webhook == null) {
            if (attachments != null)
                attachments.values().forEach(inputStream -> {
                    try { inputStream.close(); }
                    catch (IOException ignore) {}
                });
            return Optional.empty();
        }

        try {
            // Parse JSON Parameters
            JSONObject jsonObject = new JSONObject();

            // Special payload when first creating a new webhook message
            if (editMessageID == null) {
                String webName = webhook.getName();
                for (Map.Entry<Pattern, String> entry : DiscordSRV.getPlugin().getWebhookUsernameRegexes().entrySet()) {
                    webName = entry.getKey().matcher(webName).replaceAll(entry.getValue());
                }

                // Handle Discord banned words in a way that isn't against their developer policy
                String username = webName;
                username = username
                        .replaceAll("(?i)(cly)d(e)", "$1*$2")
                        .replaceAll("(?i)(d)i(scord)", "$1*$2");
                if (!username.equals(webName) && loggedBannedWords) {
                    DiscordSRV.info("Some webhook usernames are being altered to remove blocked words (eg. Clyde and Discord)");
                    loggedBannedWords = true;
                }

                jsonObject.put("username", username);

                if(webhookAvatarUrl != null) {
                    int queryStart = webhookAvatarUrl.indexOf(63);
                    jsonObject.put("avatar_url", webhookAvatarUrl.substring(0, queryStart));
                }

                // Webhooks posted to forum channels must have a thread_name or thread_id
                if(threadName != null) {
                    jsonObject.put("thread_name", threadName);
                }
            }

            // General Message payload
            if (StringUtils.isNotBlank(message)) jsonObject.put("content", message);
            if (embeds != null) {
                JSONArray jsonArray = new JSONArray();
                for (MessageEmbed embed : embeds) {
                    if (embed != null) {
                        jsonArray.put(embed.toData().toMap());
                    }
                }
                jsonObject.put("embeds", jsonArray);
            }
            if (interactions != null) {
                JSONArray jsonArray = new JSONArray();
                for (ActionRow actionRow : interactions) {
                    jsonArray.put(actionRow.toData().toMap());
                }
                jsonObject.put("components", jsonArray);
            }
            List<String> attachmentIndex = null;
            if (attachments != null) {
                attachmentIndex = new ArrayList<>(attachments.size());
                JSONArray jsonArray = new JSONArray();
                int i = 0;
                for (String name : attachments.keySet()) {
                    attachmentIndex.add(name);
                    JSONObject attachmentObject = new JSONObject();
                    attachmentObject.put("id", i);
                    attachmentObject.put("filename", name);
                    jsonArray.put(attachmentObject);
                    i++;
                }
                jsonObject.put("attachments", jsonArray);
            }

            JSONObject allowedMentions = new JSONObject();
            Set<String> parse = MessageAction.getDefaultMentions().stream()
                    .filter(Objects::nonNull)
                    .map(Message.MentionType::getParseKey)
                    .collect(Collectors.toSet());
            allowedMentions.put("parse", parse);
            jsonObject.put("allowed_mentions", allowedMentions);

            // Send payload
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Sending webhook payload: " + jsonObject);

            MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            bodyBuilder.addFormDataPart("payload_json", null, RequestBody.create(MediaType.get("application/json"), jsonObject.toString()));

            if (attachmentIndex != null) {
                for (int i = 0; i < attachmentIndex.size(); i++) {
                    String name = attachmentIndex.get(i);
                    InputStream data = attachments.get(name);
                    if (data != null) {
                        bodyBuilder.addFormDataPart("files[" + i + "]", name, new BufferedRequestBody(Okio.source(data), null));
                        data.close();
                    }
                }
            }


            Route.CompiledRoute route = (editMessageID == null)
                ? Route.Webhooks.EXECUTE_WEBHOOK.compile(webhook.getId(), webhook.getToken()).withQueryParams("wait", "true")
                : Route.Webhooks.EXECUTE_WEBHOOK_EDIT.compile(webhook.getId(), webhook.getToken(), editMessageID).withQueryParams("thread_id", editMessageID);

            DiscordPS.info("Querying URL: " + route.getCompiledRoute());

            JDAImpl jda = (JDAImpl) webhook.getJDA();

            return Optional.of(new RestActionImpl<>(jda, route, bodyBuilder.build(), (response, request) -> {
                try {
                    int status = response.code;
                    if (status == 404) {
                        // 404 = Invalid Webhook (most likely to have been deleted)
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 404, marking webhooks URLs as invalid to let them regenerate" + (allowSecondAttempt ? " & trying again" : ""));

                        // Prep the same statement to try second attempt
                        if (allowSecondAttempt) {
                            Optional<RestAction<Optional<MessageReference>>> secondAttempt = executeWebhook(webhook,
                                threadName,
                                webhookAvatarUrl,
                                editMessageID,
                                message,
                                embeds,
                                attachments,
                                interactions,
                                false);
                            if(secondAttempt.isPresent()) return secondAttempt.get().completeAfter(5, TimeUnit.SECONDS);
                        }
                        request.cancel();
                        return Optional.empty();
                    }
                    Optional<DataObject> body = response.optObject();
                    DiscordPS.info("Got API response: " + response.getString());

                    if (body.isPresent()) {

                        if (body.get().hasKey("code")) {
                            if (body.get().getInt("code") == 10015) {
                                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 10015 (Unknown Webhook)");
                                return Optional.empty();
                            }
                        }
                        DiscordPS.info("Packaging API response of: " + body.get().toPrettyString());

                        String channelID = body.get().getString("channel_id");
                        String messageID = body.get().getString("id");
                        DiscordPS.info("Received API response for webhook message delivery: " + status + " for response: " + body);

                        return Optional.of(new MessageReference(
                            Long.parseUnsignedLong(messageID),
                            Long.parseUnsignedLong(channelID),
                            DiscordSRV.getPlugin().getMainGuild().getIdLong(),
                            null,
                            webhook.getJDA())
                        );
                    }
                }
                catch (Throwable ex) {
                    DiscordPS.info("Failed to receive API response: " + ex.toString());
                }
                return Optional.empty();
            })
            );
        } catch (Exception e) {
            DiscordSRV.error("Failed to deliver webhook message to Discord: " + e.getMessage());
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, e);
            if (attachments != null) {
                attachments.values().forEach(inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                });
            }
        }
        return Optional.empty();
    }

    @Deprecated
    private static void executeWebhook(TextChannel channel, String webhookName, String webhookAvatarUrl, String editMessageId, String message, Collection<? extends MessageEmbed> embeds, Map<String, InputStream> attachments, Collection<? extends ActionRow> interactions, boolean allowSecondAttempt, boolean scheduleAsync) {
        if (channel == null) {
            if (attachments != null) {
                attachments.values().forEach(inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                });
            }
            return;
        }

        String webhookUrlForChannel = getWebhookUrlToUseForChannel(channel);
        if (webhookUrlForChannel == null) {
            if (attachments != null) {
                attachments.values().forEach(inputStream -> {
                    try {
                        inputStream.close();
                    } catch (IOException ignore) {
                    }
                });
            }
            return;
        }

        if (editMessageId != null) {
            webhookUrlForChannel += "/messages/" + editMessageId;
        }
        String webhookUrl = webhookUrlForChannel;

        Runnable task = () -> {
            try {
                JSONObject jsonObject = new JSONObject();
                if (editMessageId == null) {
                    String webName = webhookName;
                    for (Map.Entry<Pattern, String> entry : DiscordSRV.getPlugin().getWebhookUsernameRegexes().entrySet()) {
                        webName = entry.getKey().matcher(webName).replaceAll(entry.getValue());
                    }

                    // Handle Discord banned words in a way that isn't against their developer policy
                    String username = webName;
                    username = username
                            .replaceAll("(?i)(cly)d(e)", "$1*$2")
                            .replaceAll("(?i)(d)i(scord)", "$1*$2");
                    if (!username.equals(webName) && loggedBannedWords) {
                        DiscordSRV.info("Some webhook usernames are being altered to remove blocked words (eg. Clyde and Discord)");
                        loggedBannedWords = true;
                    }

                    jsonObject.put("username", username);
                    jsonObject.put("avatar_url", webhookAvatarUrl);
                }

                if (StringUtils.isNotBlank(message)) jsonObject.put("content", message);
                if (embeds != null) {
                    JSONArray jsonArray = new JSONArray();
                    for (MessageEmbed embed : embeds) {
                        if (embed != null) {
                            jsonArray.put(embed.toData().toMap());
                        }
                    }
                    jsonObject.put("embeds", jsonArray);
                }
                if (interactions != null) {
                    JSONArray jsonArray = new JSONArray();
                    for (ActionRow actionRow : interactions) {
                        jsonArray.put(actionRow.toData().toMap());
                    }
                    jsonObject.put("components", jsonArray);
                }
                List<String> attachmentIndex = null;
                if (attachments != null) {
                    attachmentIndex = new ArrayList<>(attachments.size());
                    JSONArray jsonArray = new JSONArray();
                    int i = 0;
                    for (String name : attachments.keySet()) {
                        attachmentIndex.add(name);
                        JSONObject attachmentObject = new JSONObject();
                        attachmentObject.put("id", i);
                        attachmentObject.put("filename", name);
                        jsonArray.put(attachmentObject);
                        i++;
                    }
                    jsonObject.put("attachments", jsonArray);
                }

                JSONObject allowedMentions = new JSONObject();
                Set<String> parse = MessageAction.getDefaultMentions().stream()
                        .filter(Objects::nonNull)
                        .map(Message.MentionType::getParseKey)
                        .collect(Collectors.toSet());
                allowedMentions.put("parse", parse);
                jsonObject.put("allowed_mentions", allowedMentions);

                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Sending webhook payload: " + jsonObject);

                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                bodyBuilder.addFormDataPart("payload_json", null, RequestBody.create(MediaType.get("application/json"), jsonObject.toString()));

                if (attachmentIndex != null) {
                    for (int i = 0; i < attachmentIndex.size(); i++) {
                        String name = attachmentIndex.get(i);
                        InputStream data = attachments.get(name);
                        if (data != null) {
                            bodyBuilder.addFormDataPart("files[" + i + "]", name, new BufferedRequestBody(Okio.source(data), null));
                            data.close();
                        }
                    }
                }

                github.scarsz.discordsrv.dependencies.okhttp3.Request.Builder requestBuilder = new github.scarsz.discordsrv.dependencies.okhttp3.Request.Builder()
                        .url(webhookUrl + "?wait=true")
                        .header("User-Agent", "DiscordSRV/" + DiscordSRV.getPlugin().getDescription().getVersion());
                if (editMessageId == null) {
                    requestBuilder.post(bodyBuilder.build());
                } else {
                    requestBuilder.patch(bodyBuilder.build());
                }

                OkHttpClient httpClient = DiscordSRV.getPlugin().getJda().getHttpClient();
                try (github.scarsz.discordsrv.dependencies.okhttp3.Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    int status = response.code();
                    if (status == 404) {
                        // 404 = Invalid Webhook (most likely to have been deleted)
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 404, marking webhooks URLs as invalid to let them regenerate" + (allowSecondAttempt ? " & trying again" : ""));
                        invalidWebhookUrlForChannel(channel); // tell it to get rid of the urls & get new ones
                        if (allowSecondAttempt)
                            executeWebhook(channel, webhookName, webhookAvatarUrl, editMessageId, message, embeds, attachments, interactions, false, scheduleAsync);
                        return;
                    }
                    String body = response.body().string();
                    try {
                        JSONObject jsonObj = new JSONObject(body);
                        if (jsonObj.has("code")) {
                            // 10015 = unknown webhook, https://discord.com/developers/docs/topics/opcodes-and-status-codes#json-json-error-codes
                            if (jsonObj.getInt("code") == 10015) {
                                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Webhook delivery returned 10015 (Unknown Webhook), marking webhooks url's as invalid to let them regenerate" + (allowSecondAttempt ? " & trying again" : ""));
                                invalidWebhookUrlForChannel(channel); // tell it to get rid of the urls & get new ones
                                if (allowSecondAttempt)
                                    executeWebhook(channel, webhookName, webhookAvatarUrl, editMessageId, message, embeds, attachments, interactions, false, scheduleAsync);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (editMessageId == null ? status == 204 : status == 200) {
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Received API response for webhook message delivery: " + status);
                    } else {
                        DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Received unexpected API response for webhook message delivery: " + status + " for request: " + jsonObject.toString() + ", response: " + body);
                    }
                }
            } catch (Exception e) {
                DiscordSRV.error("Failed to deliver webhook message to Discord: " + e.getMessage());
                DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, e);
                if (attachments != null) {
                    attachments.values().forEach(inputStream -> {
                        try {
                            inputStream.close();
                        } catch (IOException ignore) {
                        }
                    });
                }
            }
        };

        if (scheduleAsync) {
            SchedulerUtil.runTaskAsynchronously(DiscordSRV.getPlugin(), task);
        } else {
            task.run();
        }
    }

    /// URL Caching

    private static final Map<String, String> channelWebhookUrls = new ConcurrentHashMap<>();

    public static void invalidWebhookUrlForChannel(TextChannel textChannel) {
        String channelId = textChannel.getId();
        channelWebhookUrls.remove(channelId);
    }

    public static String getWebhookUrlToUseForChannel(TextChannel channel) {
        final String channelId = channel.getId();
        return channelWebhookUrls.computeIfAbsent(channelId, cid -> {
            List<Webhook> hooks = new ArrayList<>();
            final Guild guild = channel.getGuild();
            final Member selfMember = guild.getSelfMember();

            String bannedWebhookFormat = "DiscordSRV " + cid; // This format is blocked by Discord
            String webhookFormat = "DSRV " + cid;

            // Check if we have permission guild-wide
            List<Webhook> result;
            if (guild.getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS)) {
                result = guild.retrieveWebhooks().complete();
            } else {
                result = channel.retrieveWebhooks().complete();
            }

            result.stream()
                    .filter(webhook -> webhook.getName().startsWith(webhookFormat) || webhook.getName().startsWith(bannedWebhookFormat))
                    .filter(webhook -> {
                        // Filter to what we can modify
                        Member owner = webhook.getOwner();
                        return owner != null && selfMember.getId().equals(owner.getId());
                    })
                    .filter(webhook -> {
                        if (!webhook.getChannel().equals(channel)) {
                            webhook.delete().reason("DiscordSRV: Purging lost webhook").queue();
                            return false;
                        }
                        return true;
                    })
                    .forEach(hooks::add);

            if (hooks.isEmpty()) {
                hooks.add(createWebhook(channel, webhookFormat));
            } else if (hooks.size() > 1) {
                for (int index = 1; index < hooks.size(); index++) {
                    hooks.get(index).delete().reason("DiscordSRV: Purging duplicate webhook").queue();
                }
            }

            return hooks.stream().map(Webhook::getUrl).findAny().orElse(null);
        });
    }

    public static Webhook createWebhook(TextChannel channel, String name) {
        try {
            Webhook webhook = channel.createWebhook(name).reason("DiscordSRV: Creating webhook").complete();
            DiscordSRV.debug(Debug.MINECRAFT_TO_DISCORD, "Created webhook " + webhook.getName() + " to deliver messages to text channel #" + channel.getName());
            return webhook;
        } catch (Exception e) {
            DiscordSRV.error("Failed to create webhook " + name + " for message delivery: " + e.getMessage());
            return null;
        }
    }

    public static String getWebhookUrlFromCache(TextChannel channel) {
        return channelWebhookUrls.get(channel.getId());
    }

}