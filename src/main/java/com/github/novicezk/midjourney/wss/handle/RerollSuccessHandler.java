package com.github.novicezk.midjourney.wss.handle;


import com.github.novicezk.midjourney.Constants;
import com.github.novicezk.midjourney.enums.MessageType;
import com.github.novicezk.midjourney.enums.TaskAction;
import com.github.novicezk.midjourney.support.Task;
import com.github.novicezk.midjourney.support.TaskCondition;
import com.github.novicezk.midjourney.util.ContentParseData;
import com.github.novicezk.midjourney.util.ConvertUtils;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * reroll 消息处理.
 * 完成(create): **cat** - <@1012983546824114217> (relaxed)
 * 完成(create): **cat** - Variations by <@1012983546824114217> (relaxed)
 * 完成(create): **cat** - Variations (Strong或Subtle) by <@1012983546824114217> (relaxed)
 */
@Component
public class RerollSuccessHandler extends MessageHandler {
	private static final String CONTENT_REGEX_1 = "\\*\\*(.*?)\\*\\* - <@\\d+> \\((.*?)\\)";
	private static final String CONTENT_REGEX_2 = "\\*\\*(.*?)\\*\\* - Variations by <@\\d+> \\((.*?)\\)";
	private static final String CONTENT_REGEX_3 = "\\*\\*(.*?)\\*\\* - Variations \\(.*?\\) by <@\\d+> \\((.*?)\\)";

	@Override
	public void handle(MessageType messageType, DataObject message) {
		String content = getMessageContent(message);
		ContentParseData parseData = getParseData(content);
		if (MessageType.CREATE.equals(messageType) && parseData != null && hasImage(message)) {
			TaskCondition condition = new TaskCondition()
					.setActionSet(Set.of(TaskAction.REROLL))
					.setFinalPromptEn(parseData.getPrompt());
			findAndFinishImageTask(condition, parseData.getPrompt(), message);
		}else{
			Optional<DataObject> interaction = message.optObject("interaction");

			if (interaction.isEmpty() || "describe".equals(interaction.get().getString("name"))) {
				return;
			}

			DataArray embeds = message.getArray("embeds");
			if (embeds.isEmpty()) {
				return;
			}
			String description = embeds.getObject(0).getString("description");
			Optional<DataObject> imageOptional = embeds.getObject(0).optObject("image");
			if (imageOptional.isEmpty()) {
				return;
			}
			String imageUrl = imageOptional.get().getString("url");
			String imgName = this.discordHelper.findTaskIdWithCdnUrl(imageUrl);

			Task task = this.discordLoadBalancer.getRunningTaskByDescription("/describe "+imgName+" R");
			if (task == null) {
				return;
			}
			task.setPrompt(description);
			task.setPromptEn(description);
			task.setProperty(Constants.TASK_PROPERTY_FINAL_PROMPT, description);
			task.setProperty("rollType", "describe");
			task.setImageUrl(replaceCdnUrl(imageUrl));
			finishTask(task, message);
			task.awake();
		}
	}

	private ContentParseData getParseData(String content) {
		ContentParseData parseData = ConvertUtils.parseContent(content, CONTENT_REGEX_1);
		if (parseData == null) {
			parseData = ConvertUtils.parseContent(content, CONTENT_REGEX_2);
		}
		if (parseData == null) {
			parseData = ConvertUtils.parseContent(content, CONTENT_REGEX_3);
		}
		return parseData;
	}

}
