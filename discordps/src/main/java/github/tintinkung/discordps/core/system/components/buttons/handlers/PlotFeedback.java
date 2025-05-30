package github.tintinkung.discordps.core.system.components.buttons.handlers;

import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.MessageEmbed;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.scarsz.discordsrv.dependencies.jda.internal.utils.Checks;
import github.tintinkung.discordps.Constants;
import github.tintinkung.discordps.DiscordPS;
import github.tintinkung.discordps.core.database.WebhookEntry;
import github.tintinkung.discordps.core.system.Notification;
import github.tintinkung.discordps.core.system.components.buttons.PluginButton;
import github.tintinkung.discordps.core.system.components.buttons.PluginButtonHandler;
import github.tintinkung.discordps.core.system.components.buttons.SimpleButtonHandler;
import github.tintinkung.discordps.core.system.io.lang.PlotInteraction;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import static github.tintinkung.discordps.core.system.io.lang.Notification.ErrorMessage;

public class PlotFeedback implements PluginButtonHandler, SimpleButtonHandler {
    @Override
    public void onInteracted(@NotNull PluginButton button, ButtonClickEvent event) {
        Checks.notNull(button.getPayload(), "Feedback button payload");

        // TODO: Make feedback embed ComponentV2
        // TODO: Let plot feedback editable with markdown text
        try {
            WebhookEntry entry = WebhookEntry.getByMessageID(button.getIDLong());

            if(entry == null || entry.feedback() == null)
                throw new SQLException("Trying to get feedback that does not exist in the database!");

            MessageEmbed feedbackEmbed = new EmbedBuilder()
                    .setTitle("Your Feedback")
                    .setDescription(entry.feedback())
                    .setColor(entry.status().toTag().getColor())
                    .build();

            event.deferReply(true).addEmbeds(feedbackEmbed).queue();


        } catch (SQLException ex) {
            // Reply owner that it failed
            MessageEmbed errorEmbed = DiscordPS.getMessagesLang()
                .getEmbedBuilder(PlotInteraction.FEEDBACK_GET_FAILURE)
                .setColor(Constants.RED)
                .build();
            event.deferReply(true).addEmbeds(errorEmbed).queue();

            // Notify
            String plotID = button.getPayload();

            Notification.sendErrorEmbed(
                ErrorMessage.PLOT_FEEDBACK_GET_EXCEPTION,
                ex.toString(),
                plotID,
                event.getUser().getId()
            );
        }
    }

    @Override
    public void onInteractedBadOwner(@NotNull ButtonClickEvent event) {
        MessageEmbed notOwnerEmbed = DiscordPS.getMessagesLang()
                .getEmbedBuilder(PlotInteraction.INTERACTED_BAD_OWNER)
                .setColor(Constants.RED)
                .build();
        event.deferReply(true).addEmbeds(notOwnerEmbed).queue();
    }
}
