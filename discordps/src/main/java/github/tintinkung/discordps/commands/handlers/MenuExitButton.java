package github.tintinkung.discordps.commands.handlers;

import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.tintinkung.discordps.DiscordPS;
import github.tintinkung.discordps.core.system.components.buttons.PluginButton;
import org.jetbrains.annotations.NotNull;

/**
 * Function the same as {@link MenuButton} but exit the slash command after interacted.
 */
public class MenuExitButton extends MenuButton {

    @Override
    public void onInteracted(@NotNull PluginButton button, @NotNull ButtonClickEvent event) {
        super.onInteracted(button, event);

        DiscordPS.getPlugin().exitSlashCommand(button.getIDLong());
    }
}
