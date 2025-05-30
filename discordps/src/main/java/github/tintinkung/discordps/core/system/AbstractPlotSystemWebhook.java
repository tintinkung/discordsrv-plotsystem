package github.tintinkung.discordps.core.system;

import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageReference;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.ActionRow;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.Button;
import github.tintinkung.discordps.DiscordPS;
import github.tintinkung.discordps.api.events.PlotEvent;
import github.tintinkung.discordps.core.database.ThreadStatus;
import github.tintinkung.discordps.core.database.WebhookEntry;
import github.tintinkung.discordps.core.system.components.api.ComponentV2;
import github.tintinkung.discordps.core.system.io.LanguageFile;
import github.tintinkung.discordps.core.system.io.lang.Format;
import github.tintinkung.discordps.core.system.io.lang.PlotInformation;
import github.tintinkung.discordps.core.system.io.lang.PlotNotification;
import github.tintinkung.discordps.core.system.layout.Layout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;


import static github.tintinkung.discordps.core.system.io.lang.Notification.ErrorMessage;

sealed abstract class AbstractPlotSystemWebhook permits PlotSystemWebhook {

    /**
     * The webhook instance, provide all functionality in webhook management.
     */
    protected final ForumWebhook webhook;

    /**
     * memorized metadata per instance
     *
     * @see Metadata
     */
    protected final Metadata metadata;

    /**
     * Initialize webhook instance
     *
     * @param webhook The forum webhook manager instance
     */
    protected AbstractPlotSystemWebhook(ForumWebhook webhook) {
        this.webhook = webhook;
        this.metadata = new Metadata(
            DiscordPS.getMessagesLang().get(PlotInformation.HELP_LABEL),
            DiscordPS.getMessagesLang().get(PlotInformation.REJECTED_FEEDBACK_LABEL),
            DiscordPS.getMessagesLang().get(PlotInformation.APPROVED_FEEDBACK_LABEL),
            DiscordPS.getMessagesLang().get(PlotInformation.REJECTED_NO_FEEDBACK_LABEL),
            DiscordPS.getMessagesLang().get(PlotInformation.APPROVED_NO_FEEDBACK_LABEL),
            Button.link(
                DiscordPS.getMessagesLang().get(PlotInformation.DOCS_URL),
                DiscordPS.getMessagesLang().get(PlotInformation.DOCS_LABEL)
            )
        );
    }

    /**
     * Represent action that update layout component,
     * invoking with {@link Layout} which process data to
     * {@link github.tintinkung.discordps.core.system.WebhookDataBuilder.WebhookData} ready to request to API
     */
    @FunctionalInterface
    protected interface LayoutUpdater extends Function<Layout, Optional<WebhookDataBuilder.WebhookData>> {}

    /**
     * Represent action that update message status of a plot entry,
     * invoking with {@link Message} which process data to
     * {@link github.tintinkung.discordps.core.system.WebhookDataBuilder.WebhookData} ready to request to API
     */
    @FunctionalInterface
    protected interface MessageUpdater extends Function<Message, Optional<WebhookDataBuilder.WebhookData>> {}

    /**
     * Restore thread's component layout from thread ID
     *
     * @param threadID The thread ID as snowflake string
     * @return The future when completed, return the thread layout as an optional (empty if failed)
     */
    public @NotNull CompletableFuture<Optional<Layout>> getThreadLayout(String threadID) {
        return webhook.getInitialLayout(threadID, true).submit();
    }

    /**
     * Query update to status entry for the giving message ID
     *
     * @param messageID The entry as message ID to update
     * @param status The status to update into
     * @param event Update event for debugging if an SQL exception occurs
     */
    public void updateEntryStatus(long messageID, ThreadStatus status, PlotEvent event) {
        try {
            WebhookEntry.updateThreadStatus(messageID, status);
        } catch (SQLException ex) {
            Notification.sendErrorEmbed(
                ErrorMessage.PLOT_UPDATE_SQL_EXCEPTION,
                ex.toString(),
                String.valueOf(event.getPlotID()),
                event.getClass().getSimpleName()
            );
        }
    }

    /**
     * Create new thread for the given plot ID,
     * will fall back to {@link #addNewExistingPlot(PlotSystemThread, Consumer)}
     * if an entry already exist indication a re-claim plot.
     *
     * @param thread   The plot ID to be created
     * @param register If true, will register this plot in database entry.
     * @param force    By default, will check if the plot already exist in the database or not and it will override that plot entry if existed.
     *                 By setting this to {@code true}, will ignore the checks and create new thread forcefully.
     * @return Null only if plot entry does not exist per plot-system database, else a handled completable future.
     */
    protected abstract CompletableFuture<Void> newThreadForPlotID(@NotNull PlotSystemThread thread,
                                                                  boolean register,
                                                                  boolean force);

    /**
     * Create and register a plot status by a given plot data.
     * Will create an initial status message with help button and documentation link.
     *
     * @param plotData The plot information
     * @param plotID The plot ID
     * @param threadID The thread ID this plot is created on
     */
    protected abstract void registerNewPlot(@NotNull PlotData plotData,
                                            int plotID,
                                            long threadID);

    /**
     * Wrapper for {@link #registerNewPlot(PlotData, int, long)}
     * to register new plot based on the initial message reference.
     *
     * @param plotData The plot data to register this plot
     * @param plotID The plot ID to register this plot
     * @param initialMessage Action required to receive message reference for registering this plot.
     *                       Conventionally is using as {@link Optional#ifPresentOrElse(Consumer, Runnable)}
     */
    protected void registerNewPlot(PlotData plotData,
                                   int plotID,
                                   @NotNull BiConsumer<Consumer<MessageReference>, Runnable> initialMessage) {
        initialMessage.accept(
            message -> this.registerNewPlot(plotData, plotID, message.getMessageIdLong()),
            () -> Notification.notify(ErrorMessage.PLOT_CREATE_UNKNOWN_EXCEPTION)
        );
    }

    /**
     * Suppose that a plot entry exist, fetch it with a new plot-system data as a new plot entry.
     *
     * @param thread The thread provider to get data from
     * @param onSuccess Action invoked on success with the plot's information
     * @return The handled future that completed when all queued action is done
     */
    @Nullable
    protected abstract CompletableFuture<Void> addNewExistingPlot(@NotNull PlotSystemThread thread,
                                                                  @Nullable Consumer<PlotData> onSuccess);

    /**
     * Suppose that a plot entry exist,
     * fetch it with from the existing entry and register if enabled.
     * This will fetch the plot as {@link github.tintinkung.discordps.api.events.PlotReclaimEvent} internally.
     *
     * @param entry The existing entry to be reclaimed as
     * @param register Whether to register this plot to database ot make untracked
     * @return The handled future that completed when all queued action is done
     */
    @Nullable
    protected CompletableFuture<Void> addNewExistingPlot(@NotNull WebhookEntry entry, boolean register) {
        final Consumer<PlotData> registerNewPlot = plotData -> this.registerNewPlot(plotData, entry.plotID(), entry.threadID());
        final PlotSystemThread thread = new PlotSystemThread(entry.plotID(), entry.threadID());

        return this.addNewExistingPlot(thread, register? registerNewPlot : null);
    }

    /**
     * Update plot by the given action with specified event.
     *
     * @param action The update action that specify what plot entry to be updated
     * @param event The referring event that trigger this action, null event will be defined as a system fetch
     * @param status The primary status to update to
     * @return The same action as a future that is completed when all staged action is completed
     * @param <T> The type of referring event that activate this action
     */
    @NotNull
    public abstract <T extends PlotEvent>
    CompletableFuture<PlotSystemThread.UpdateAction> updatePlot(@NotNull PlotSystemThread.UpdateAction action,
                                                                @Nullable T event,
                                                                @NotNull ThreadStatus status);

    /**
     * Update plot by the given event creating a new {@link PlotSystemThread.UpdateAction#fromEvent(PlotEvent)}
     * for updating plot by the event information.
     *
     * <p>Note: This will fetch a latest plot entry to be updated.
     * To update more specifically, provide {@link PlotSystemThread.UpdateAction}
     * and use {@link #updatePlot(PlotSystemThread.UpdateAction, PlotEvent, ThreadStatus)} instead.</p>
     *
     * @param event The referring event that trigger this action
     * @param status The primary status to update to
     * @param whenComplete Invoked when the update action is completed returning this action
     * @param <T> The type of referring event that activate this action
     */
    public <T extends PlotEvent> void updatePlot(@NotNull T event,
                                                 @NotNull ThreadStatus status,
                                                 @NotNull Consumer<PlotSystemThread.UpdateAction> whenComplete) {
        PlotSystemThread.UpdateAction action = PlotSystemThread.UpdateAction.fromEvent(event);
        if(action == null) return;
        this.updatePlot(action, event, status).thenAccept(whenComplete);
    }

    /**
     * Create a new plot thread to webhook forum as well as attach media file to its initial message.
     *
     * @param threadName The thread name
     * @param plotData The plot data information
     * @param componentsV2 The layout component the thread will be initialized with
     * @return Queued future to be handled, on success will return
     *         a non-empty optional with the initial message reference as value
     */
    @NotNull
    public CompletableFuture<Optional<MessageReference>> createNewPlotThread(@NotNull String threadName,
                                                                             @NotNull PlotData plotData,
                                                                             @NotNull Collection<? extends ComponentV2> componentsV2) {

        WebhookDataBuilder.WebhookData data = new WebhookDataBuilder()
                .setThreadName(threadName)
                .setComponentsV2(componentsV2)
                .forceComponentV2()
                .build();

        // Attach files
        plotData.getAvatarFile().ifPresent(data::addFile);
        if(!plotData.getImageFiles().isEmpty()) plotData.getImageFiles().forEach(data::addFile);

        return this.webhook
            .newThreadFromWebhook(data, plotData.getStatusTags(), true, true)
            .submit();
    }

    /**
     * Create interactive layout by status as follows:
     * <ul>
     *     <li>{@code on_going} {@code finished} Help & Docs button</li>
     *     <li>{@code approved} Approval feedback button</li>
     *     <li>{@code rejected} Rejected reason button</li>
     * </ul>
     * <p>Note: interactive button will not be added if owner does not have a discord account.</p>
     *
     * @param plotID The plot ID of this interaction
     * @param messageID The message ID to attach this interaction
     * @param status The plot status to determined interaction layout
     * @param owner The plot owner that can interaction with the layout
     * @return The component layout as a singleton list of action row, null if the given status has no interactions
     */
    protected @Nullable List<ActionRow> createInitialInteraction(int plotID,
                                                                 long messageID,
                                                                 @NotNull ThreadStatus status,
                                                                 @NotNull MemberOwnable owner) {
        ActionRow documentationRow = ActionRow.of(this.metadata.documentationButton());

        Function<Long, ActionRow> interactionRow = userID -> ActionRow.of(
            this.newHelpButton(messageID, userID, plotID),
            this.metadata.documentationButton()
        );

        Function<Long, ActionRow> approvedRow = userID -> ActionRow.of(Button.success(
            AvailableButton.FEEDBACK_BUTTON.resolve(messageID, userID, plotID),
            this.metadata.approvedFeedbackLabel())
        );

        Function<Long, ActionRow> rejectedRow = userID -> ActionRow.of(Button.danger(
            AvailableButton.FEEDBACK_BUTTON.resolve(messageID, userID, plotID),
            this.metadata.rejectedFeedbackLabel())
        );

        // Interactive row if owner has discord to interact with it
        List<ActionRow> interactiveRow = switch (status) {
            case on_going, finished -> List.of(interactionRow.apply(owner.getOwnerDiscord().get().getIdLong()));
            case approved -> List.of(approvedRow.apply(owner.getOwnerDiscord().get().getIdLong()));
            case rejected -> List.of(rejectedRow.apply(owner.getOwnerDiscord().get().getIdLong()));
            default -> null;
        };

        // Static row if owner discord is not present
        List<ActionRow> staticRow = switch (status) {
            case on_going, finished, rejected -> List.of(documentationRow);
            default -> null;
        };

        return owner.getOwnerDiscord().isPresent()? interactiveRow : staticRow;
    }

    /**
     * Send a plot notification message to a thread
     *
     * @param type The notification message to send to
     * @param threadID The thread ID to send notification to
     * @param content The content of the notification as a modifier function
     */
    protected abstract void sendNotification(@NotNull PlotNotification type,
                                             @NotNull String threadID,
                                             @NotNull Function<String, String> content);

    /**
     * Send a plot notification message with no placeholder variable
     *
     * @param type  The notification message to send to
     * @param threadID The thread ID to send notification to
     */
    public void sendNotification(@NotNull PlotNotification type,
                                 @NotNull String threadID) {
        this.sendNotification(type, threadID, Function.identity());
    }

    /**
     * Send a plot notification message with owner ID placeholder
     *
     * @param type The notification message to send to
     * @param threadID The thread ID to send notification to
     *                 <i>(placeholder: {@code {threadID}})</i>
     * @param owner Owner ID to apply to this notification message
     *              <i>(placeholder: {@code {owner}})</i>
     */
    public void sendNotification(@NotNull PlotNotification type,
                                 @NotNull String threadID,
                                 @Nullable String owner) {
        this.sendNotification(type, threadID, content -> content
                .replace(Format.THREAD_ID, threadID)
                .replace(Format.OWNER, owner == null? LanguageFile.NULL_LANG : owner));
    }

    /**
     * Special notification {@link PlotNotification#ON_REVIEWED} that notifies the review button label.
     *
     * @param threadID The thread ID to send notification to
     * @param owner  Owner ID to apply to this notification message
     *               <i>(placeholder: {@code {owner}})</i>
     * @param label  Button label apply to this notification message
     *               <i>(placeholder: {@code {label}})</i>
     */
    protected void sendNotification(@NotNull String threadID,
                                    @NotNull String owner,
                                    @NotNull String label) {
        this.sendNotification(PlotNotification.ON_REVIEWED, threadID, content -> content
                .replace(Format.USER_ID, owner)
                .replace(Format.LABEL, label));
    }

    protected Button newHelpButton(long messageID, long ownerID, int plotID) {
        return Button.primary(AvailableButton.HELP_BUTTON.resolve(messageID, ownerID, plotID), this.metadata.helpButtonLabel());
    }

    protected static final Consumer<? super Throwable> ON_PLOT_OVERRIDING_EXCEPTION = error -> {
        DiscordPS.error("Error occurred adding new plot to existing data.", error);
        Notification.sendErrorEmbed(ErrorMessage.PLOT_UPDATE_UNKNOWN_EXCEPTION, error.toString());
    };

    protected static final BiConsumer<Integer, ? super Throwable> ON_PLOT_CREATION_EXCEPTION = (plotID, error) -> {
        DiscordPS.error("Failed to resolve data trying to create new plot.", error);
        Notification.sendErrorEmbed(ErrorMessage.PLOT_CREATE_EXCEPTION, error.toString(), String.valueOf(plotID));
    };

    protected static final BiConsumer<Integer, ? super Throwable> ON_PLOT_REGISTER_EXCEPTION = (plotID, error) -> {
        DiscordPS.error("Failed to resolve data trying to register new plot to database.", error);
        Notification.sendErrorEmbed(ErrorMessage.PLOT_REGISTER_ENTRY_EXCEPTION, error.toString(), String.valueOf(plotID));
    };

    protected static final Consumer<? super Throwable> ON_PLOT_FEEDBACK_EXCEPTION = error -> {
        DiscordPS.error("Error occurred while setting plot's feedback data.", error);
        Notification.sendErrorEmbed(ErrorMessage.PLOT_FEEDBACK_UNKNOWN_EXCEPTION, error.toString());
    };

    protected static final BiConsumer<PlotEvent, ? super Throwable> ON_PLOT_UPDATE_EXCEPTION = (event, error) -> {
        DiscordPS.error("Error occurred while updating plot data.", error);
        Notification.sendErrorEmbed(
            ErrorMessage.PLOT_UPDATE_EXCEPTION,
            error.toString(),
            String.valueOf(event.getPlotID()),
            event.getClass().getSimpleName()
        );
    };

    protected static final BiConsumer<Optional<?>, ? super Throwable> HANDLE_BUTTON_ATTACH_ERROR = (success, failure) -> {
        if(failure != null) {
            DiscordPS.error("A thread interaction attach action returned an exception", failure);
            Notification.sendErrorEmbed(ErrorMessage.FAILED_ATTACH_BUTTON, failure.toString());
        }
    };

    protected static final BiConsumer<Optional<?>, ? super Throwable> HANDLE_THREAD_EDIT_ERROR = (success, failure) -> {
        if(failure != null) {
            DiscordPS.error("A thread data update action returned an exception", failure);
            Notification.sendErrorEmbed(ErrorMessage.FAILED_THREAD_EDIT, failure.toString());
        }
    };

    protected static final BiConsumer<Optional<?>, ? super Throwable> HANDLE_MESSAGE_EDIT_ERROR = (success, failure) -> {
        if(failure != null) {
            DiscordPS.error("A thread message update action returned an exception", failure);
            Notification.sendErrorEmbed(ErrorMessage.FAILED_MESSAGE_EDIT, failure.toString());
        }
    };

    protected static final BiConsumer<Optional<?>, ? super Throwable> HANDLE_LAYOUT_EDIT_ERROR = (success, failure) -> {
        if(failure != null) {
            DiscordPS.error("A thread layout update action returned an exception", failure);
            Notification.sendErrorEmbed(ErrorMessage.FAILED_LAYOUT_EDIT, failure.toString());
        }
    };


    /**
     * Plot Metadata from messages language file, mostly button label data.
     * <p>Below are the default label of each buttons</p>
     *
     * @param helpButtonLabel "Help" label
     * @param rejectedFeedbackLabel "Show Reason" label
     * @param approvedFeedbackLabel "View Feedback" label
     * @param rejectedNoFeedbackLabel "No Feedback Yet" label
     * @param approvedNoFeedbackLabel "No Feedback Yet" label
     * @param documentationButton Documentation URL button
     */
    protected record Metadata(
            String helpButtonLabel,
            String rejectedFeedbackLabel,
            String approvedFeedbackLabel,
            String rejectedNoFeedbackLabel,
            String approvedNoFeedbackLabel,
            Button documentationButton
    ) { }
}