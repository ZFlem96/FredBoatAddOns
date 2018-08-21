/*
 *
 * MIT License
 *
 * Copyright (c) 2017 Frederik Ar. Mikkelsen
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package fredboat.messaging;

import fredboat.commandmeta.abs.CommandContext;
import fredboat.feature.I18n;
import fredboat.feature.metrics.Metrics;
import fredboat.messaging.internal.Context;
import fredboat.shared.constant.BotConstants;
import io.prometheus.client.Collector;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.exceptions.ErrorResponseException;
import net.dv8tion.jda.core.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.core.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * Created by napster on 10.09.17.
 * <p>
 * Everything related to sending RestActions from FredBoat
 */
@SuppressWarnings("UnusedReturnValue")
public class CentralMessaging {

    @Nonnull
    private static final Logger log = LoggerFactory.getLogger(CentralMessaging.class);

    //this is needed for when we absolutely don't care about a rest action failing (use this only after good consideration!)
    // because if we pass null for a failure handler to JDA it uses a default handler that results in a warning/error level log
    @Nonnull
    public static final Consumer<Throwable> NOOP_EXCEPTION_HANDLER = __ -> {
    };

    //use this to schedule rest actions whenever queueAfter() or similar JDA methods would be used
    // this makes it way easier to track stats + handle failures of such delayed RestActions
    // instead of implementing a ton of overloaded methods in this class
    @Nonnull
    public static final ScheduledExecutorService restService = Executors.newScheduledThreadPool(10,
            runnable -> new Thread(runnable, "central-messaging-scheduler"));


    // ********************************************************************************
    //       Thread local handling and providing of Messages and Embeds builders
    // ********************************************************************************

    //instead of creating millions of MessageBuilder and EmbedBuilder objects we're going to reuse the existing ones, on
    // a per-thread scope
    // this makes sense since the vast majority of message processing in FredBoat is happening in the main JDA threads

    @Nonnull
    private static ThreadLocal<MessageBuilder> threadLocalMessageBuilder = ThreadLocal.withInitial(MessageBuilder::new);
    @Nonnull
    private static ThreadLocal<EmbedBuilder> threadLocalEmbedBuilder = ThreadLocal.withInitial(EmbedBuilder::new);

    @Nonnull
    public static MessageBuilder getClearThreadLocalMessageBuilder() {
        return threadLocalMessageBuilder.get().clear();
    }

    //presets fredboat color on a clear embed
    @Nonnull
    public static EmbedBuilder getColoredEmbedBuilder() {
        return getClearThreadLocalEmbedBuilder()
                .setColor(BotConstants.FREDBOAT_COLOR);
    }

    @Nonnull
    public static EmbedBuilder getClearThreadLocalEmbedBuilder() {
        return threadLocalEmbedBuilder.get()
                .clearFields()
                .setTitle(null)
                .setDescription(null)
                .setTimestamp(null)
                .setColor(null)
                .setThumbnail(null)
                .setAuthor(null, null, null)
                .setFooter(null, null)
                .setImage(null);
    }

    //May not be an empty string, as MessageBuilder#build() will throw an exception
    @Nonnull
    public static Message from(@Nonnull String string) {
        return getClearThreadLocalMessageBuilder().append(string).build();
    }

    @Nonnull
    public static Message from(@Nonnull MessageEmbed embed) {
        return getClearThreadLocalMessageBuilder().setEmbed(embed).build();
    }


    // ********************************************************************************
    //                     Entry methods for message sending
    // ********************************************************************************

    @Nonnull
    @CheckReturnValue
    public static JdaMessageActionBuilder message(@Nonnull MessageChannel channel, @Nonnull Message message) {
        return new JdaMessageActionBuilder(channel, message);
    }

    // String
    @Nonnull
    @CheckReturnValue
    public static JdaMessageActionBuilder message(@Nonnull MessageChannel channel, @Nonnull String content) {
        return new JdaMessageActionBuilder(channel, from(content));
    }

    // Embed
    @Nonnull
    @CheckReturnValue
    public static JdaMessageActionBuilder message(@Nonnull MessageChannel channel, @Nonnull MessageEmbed embed) {
        return new JdaMessageActionBuilder(channel, from(embed));
    }

    // ********************************************************************************
    //                            Message editing methods
    // ********************************************************************************

    /**
     * Combines the benefits of JDAs RestAction#queue and returning a Future that can be get() if necessary
     *
     * @param oldMessage The message to be edited
     * @param newMessage The message to be set
     * @param onSuccess  Optional success handler
     * @param onFail     Optional exception handler
     * @return Future that can be waited on in case the code requires completion. Similar to JDA's RestAction#complete,
     * avoid usage where not absolutely needed.
     */
    @Nonnull
    public static MessageFuture editMessage(@Nonnull Message oldMessage, @Nonnull Message newMessage,
                                            @Nullable Consumer<Message> onSuccess, @Nullable Consumer<Throwable> onFail) {
        return editMessage0(
                oldMessage.getChannel(),
                oldMessage.getIdLong(),
                newMessage,
                onSuccess,
                onFail
        );
    }

    @Nonnull
    public static MessageFuture editMessage(@Nonnull Message oldMessage, @Nonnull Message newMessage) {
        return editMessage0(
                oldMessage.getChannel(),
                oldMessage.getIdLong(),
                newMessage,
                null,
                null
        );
    }

    @Nonnull
    public static MessageFuture editMessage(@Nonnull Message oldMessage, @Nonnull String newContent) {
        return editMessage0(
                oldMessage.getChannel(),
                oldMessage.getIdLong(),
                from(newContent),
                null,
                null
        );
    }

    @Nonnull
    public static MessageFuture editMessage(@Nonnull MessageChannel channel, long oldMessageId, @Nonnull Message newMessage,
                                            @Nullable Consumer<Message> onSuccess, @Nullable Consumer<Throwable> onFail) {
        return editMessage0(
                channel,
                oldMessageId,
                newMessage,
                onSuccess,
                onFail
        );
    }

    @Nonnull
    public static MessageFuture editMessage(@Nonnull MessageChannel channel, long oldMessageId, @Nonnull Message newMessage) {
        return editMessage0(
                channel,
                oldMessageId,
                newMessage,
                null,
                null
        );
    }

    @Nonnull
    public static MessageFuture editMessage(@Nonnull MessageChannel channel, long oldMessageId, @Nonnull String newContent) {
        return editMessage0(
                channel,
                oldMessageId,
                from(newContent),
                null,
                null
        );
    }

    // ********************************************************************************
    //                   Miscellaneous messaging related methods
    // ********************************************************************************

    public static void sendTyping(@Nonnull MessageChannel channel) {
        try {
            channel.sendTyping().queue(
                    __ -> Metrics.successfulRestActions.labels("sendTyping").inc(),
                    getJdaRestActionFailureHandler("Could not send typing event in channel " + channel.getId())
            );
        } catch (InsufficientPermissionException e) {
            handleInsufficientPermissionsException(channel, e);
        }
    }

    //make sure that all the messages are from the channel you provide
    public static void deleteMessages(@Nonnull TextChannel channel, @Nonnull Collection<Message> messages) {
        if (!messages.isEmpty()) {
            try {
                channel.deleteMessages(messages).queue(
                        __ -> Metrics.successfulRestActions.labels("bulkDeleteMessages").inc(),
                        getJdaRestActionFailureHandler(String.format("Could not bulk delete %s messages in channel %s",
                                messages.size(), channel.getId()))
                );
            } catch (InsufficientPermissionException e) {
                handleInsufficientPermissionsException(channel, e);
            }
        }
    }

    public static void deleteMessageById(@Nonnull MessageChannel channel, long messageId) {
        try {
            channel.getMessageById(messageId).queue(
                    message -> {
                        Metrics.successfulRestActions.labels("getMessageById").inc();
                        CentralMessaging.deleteMessage(message);
                    },
                    NOOP_EXCEPTION_HANDLER //prevent logging an error if that message could not be found in the first place
            );
        } catch (InsufficientPermissionException e) {
            handleInsufficientPermissionsException(channel, e);
        }
    }

    //make sure that the message passed in here is actually existing in Discord
    // e.g. dont pass messages in here that were created with a MessageBuilder in our code
    public static void deleteMessage(@Nonnull Message message) {
        try {
            message.delete().queue(
                    __ -> Metrics.successfulRestActions.labels("deleteMessage").inc(),
                    getJdaRestActionFailureHandler(String.format("Could not delete message %s in channel %s with content\n%s",
                            message.getId(), message.getChannel().getId(), message.getContentRaw()))
            );
        } catch (InsufficientPermissionException e) {
            handleInsufficientPermissionsException(message.getChannel(), e);
        }
    }

    @Nonnull
    public static EmbedBuilder addFooter(@Nonnull EmbedBuilder eb, @Nonnull Member author) {
        return eb.setFooter(author.getEffectiveName(), author.getUser().getAvatarUrl());
    }

    @Nonnull
    public static EmbedBuilder addNpFooter(@Nonnull EmbedBuilder eb, @Nonnull Member requester) {
        return eb.setFooter("Requested by " + requester.getEffectiveName(), requester.getUser().getAvatarUrl());
    }


    // ********************************************************************************
    //                           Class internal methods
    // ********************************************************************************

    //class internal message sending method
    @Nonnull
    private static MessageFuture sendMessage0(JdaMessageActionBuilder messageActionBuilder, @Nullable Context source) {
        MessageChannel channel = messageActionBuilder.getTargetChannel();
        Message message = messageActionBuilder.getContent();
        Consumer<Message> onSuccess = messageActionBuilder.getSuccess();
        Consumer<Throwable> onFail = messageActionBuilder.getFailure();

        MessageFuture result = new MessageFuture();
        Consumer<Message> successWrapper = m -> {
            result.complete(m);
            Metrics.successfulRestActions.labels("sendMessage").inc();
            if (source instanceof CommandContext) {
                CommandContext context = (CommandContext) source;
                long in = context.msg.getCreationTime().toInstant().toEpochMilli();
                long out = m.getCreationTime().toInstant().toEpochMilli();
                String label = context.command.getClass().getSimpleName();
                Metrics.totalResponseTime.labels(label).observe((out - in) / Collector.MILLISECONDS_PER_SECOND);
            }
            if (onSuccess != null) {
                onSuccess.accept(m);
            }
        };
        Consumer<Throwable> failureWrapper = t -> {
            result.completeExceptionally(t);
            if (onFail != null) {
                onFail.accept(t);
            } else {
                String info = String.format("Could not sent message\n%s\nto channel %s in guild %s",
                        message.getContentRaw(), channel.getId(),
                        (channel instanceof TextChannel) ? ((TextChannel) channel).getGuild().getIdLong() : "null");
                getJdaRestActionFailureHandler(info).accept(t);
            }
        };

        try {
            channel.sendMessage(message).queue(successWrapper, failureWrapper);
        } catch (InsufficientPermissionException e) {
            if (onFail != null) {
                onFail.accept(e);
            }
            if (e.getPermission() == Permission.MESSAGE_EMBED_LINKS) {
                handleInsufficientPermissionsException(channel, e);
            } else {
                //do not call CentralMessaging#handleInsufficientPermissionsException() from here as that will result in a loop
                log.warn("Could not send message to channel {} due to missing permission {}", channel.getIdLong(), e.getPermission().getName(), e);
            }
        }
        return result;
    }

    //class internal editing method
    @Nonnull
    private static MessageFuture editMessage0(@Nonnull MessageChannel channel, long oldMessageId, @Nonnull Message newMessage,
                                              @Nullable Consumer<Message> onSuccess, @Nullable Consumer<Throwable> onFail) {

        MessageFuture result = new MessageFuture();
        Consumer<Message> successWrapper = m -> {
            result.complete(m);
            Metrics.successfulRestActions.labels("editMessage").inc();
            if (onSuccess != null) {
                onSuccess.accept(m);
            }
        };
        Consumer<Throwable> failureWrapper = t -> {
            result.completeExceptionally(t);
            if (onFail != null) {
                onFail.accept(t);
            } else {
                String info = String.format("Could not edit message %s in channel %s in guild %s with new content %s",
                        oldMessageId, channel.getId(),
                        (channel instanceof TextChannel) ? ((TextChannel) channel).getGuild().getIdLong() : "null",
                        newMessage.getContentRaw());
                getJdaRestActionFailureHandler(info).accept(t);
            }
        };

        try {
            channel.editMessageById(oldMessageId, newMessage).queue(successWrapper, failureWrapper);
        } catch (InsufficientPermissionException e) {
            if (onFail != null) {
                onFail.accept(e);
            }
            handleInsufficientPermissionsException(channel, e);
        }
        return result;
    }

    public static void handleInsufficientPermissionsException(@Nonnull MessageChannel channel,
                                                              @Nonnull InsufficientPermissionException e) {
        final ResourceBundle i18n;
        if (channel instanceof TextChannel) {
            i18n = I18n.get(((TextChannel) channel).getGuild());
        } else {
            i18n = I18n.DEFAULT.getProps();
        }
        //only ever try sending a simple string from here so we don't end up handling a loop of insufficient permissions
        message(channel, i18n.getString("permissionMissingBot") + " **" + e.getPermission().getName() + "**")
                .send(null);
    }


    //handles failed JDA rest actions by logging them with an informational string and optionally ignoring some error response codes
    @Nonnull
    public static Consumer<Throwable> getJdaRestActionFailureHandler(@Nonnull String info, ErrorResponse... ignored) {
        return t -> {
            if (t instanceof ErrorResponseException) {
                ErrorResponseException e = (ErrorResponseException) t;
                Metrics.failedRestActions.labels(Integer.toString(e.getErrorCode())).inc();
                if (Arrays.asList(ignored).contains(e.getErrorResponse())
                        || e.getErrorCode() == -1) { //socket timeout, fuck those
                    return;
                }
            }
            log.warn(info, t);
        };
    }

    /**
     * Created by napster on 17.03.18.
     * <p>
     * Container class to tame the bouquet of overloaded message sending methods of {@link CentralMessaging}
     */
    @space.npstr.annotations.FieldsAreNonNullByDefault
    @space.npstr.annotations.ParametersAreNonnullByDefault
    @space.npstr.annotations.ReturnTypesAreNonNullByDefault
    public static class JdaMessageActionBuilder {

        private MessageChannel targetChannel;

        private Message content;

        @Nullable
        private Consumer<Message> success;

        @Nullable
        private Consumer<Throwable> failure;

        public JdaMessageActionBuilder(MessageChannel targetChannel, Message content) {
            this.targetChannel = targetChannel;
            this.content = content;
        }

        /**
         * @param context Pass the context of the place calling this. This is used for instrumentation for example
         */
        public MessageFuture send(@Nullable Context context) {
            return sendMessage0(this, context);
        }

        /**
         * @param targetChannel The channel that should be messaged
         */
        @CheckReturnValue
        public JdaMessageActionBuilder channel(MessageChannel targetChannel) {
            this.targetChannel = targetChannel;
            return this;
        }

        public MessageChannel getTargetChannel() {
            return targetChannel;
        }

        /**
         * @param content Message to be sent
         */
        @CheckReturnValue
        public JdaMessageActionBuilder content(Message content) {
            this.content = content;
            return this;
        }

        public Message getContent() {
            return content;
        }

        /**
         * @param success Optional success handler
         */
        @CheckReturnValue
        public JdaMessageActionBuilder success(@Nullable Consumer<Message> success) {
            this.success = success;
            return this;
        }

        @Nullable
        public Consumer<Message> getSuccess() {
            return success;
        }

        /**
         * @param failure Optional exception handler
         */
        @CheckReturnValue
        public JdaMessageActionBuilder failure(@Nullable Consumer<Throwable> failure) {
            this.failure = failure;
            return this;
        }

        @Nullable
        public Consumer<Throwable> getFailure() {
            return failure;
        }

    }
}
