package com.github.novicezk.midjourney.controller;

import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.RandomUtil;
import com.github.novicezk.midjourney.Constants;
import com.github.novicezk.midjourney.ProxyProperties;
import com.github.novicezk.midjourney.ReturnCode;
import com.github.novicezk.midjourney.dto.BaseSubmitDTO;
import com.github.novicezk.midjourney.dto.SubmitBlendDTO;
import com.github.novicezk.midjourney.dto.SubmitChangeDTO;
import com.github.novicezk.midjourney.dto.SubmitDescribeDTO;
import com.github.novicezk.midjourney.dto.SubmitImagineDTO;
import com.github.novicezk.midjourney.dto.SubmitSimpleChangeDTO;
import com.github.novicezk.midjourney.enums.TaskAction;
import com.github.novicezk.midjourney.enums.TaskStatus;
import com.github.novicezk.midjourney.exception.BannedPromptException;
import com.github.novicezk.midjourney.result.SubmitResultVO;
import com.github.novicezk.midjourney.service.TaskService;
import com.github.novicezk.midjourney.service.TaskStoreService;
import com.github.novicezk.midjourney.service.TranslateService;
import com.github.novicezk.midjourney.support.Task;
import com.github.novicezk.midjourney.support.TaskCondition;
import com.github.novicezk.midjourney.util.BannedPromptUtils;
import com.github.novicezk.midjourney.util.ConvertUtils;
import com.github.novicezk.midjourney.util.MimeTypeUtils;
import com.github.novicezk.midjourney.util.SnowFlake;
import com.github.novicezk.midjourney.util.TaskChangeParams;
import eu.maxschuster.dataurl.DataUrl;
import eu.maxschuster.dataurl.DataUrlSerializer;
import eu.maxschuster.dataurl.IDataUrlSerializer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Api(tags = "任务提交")
@RestController
@RequestMapping("/submit")
@RequiredArgsConstructor
public class SubmitController {
	private final TranslateService translateService;
	private final TaskStoreService taskStoreService;
	private final ProxyProperties properties;
	private final TaskService taskService;

	@ApiOperation(value = "提交Imagine任务")
	@PostMapping("/imagine")
	@CrossOrigin(origins = "*")
	public SubmitResultVO imagine(@RequestBody SubmitImagineDTO imagineDTO) {
		String prompt = imagineDTO.getPrompt();
		if (CharSequenceUtil.isBlank(prompt)) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "prompt不能为空");
		}
		prompt = prompt.trim();
		Task task = newTask(imagineDTO);
		task.setAction(TaskAction.IMAGINE);
		task.setPrompt(prompt);
		String promptEn = translatePrompt(prompt);
		try {
			BannedPromptUtils.checkBanned(promptEn);
		} catch (BannedPromptException e) {
			return SubmitResultVO.fail(ReturnCode.BANNED_PROMPT, "可能包含敏感词")
					.setProperty("promptEn", promptEn).setProperty("bannedWord", e.getMessage());
		}
		List<String> base64Array = Optional.ofNullable(imagineDTO.getBase64Array()).orElse(new ArrayList<>());
		if (CharSequenceUtil.isNotBlank(imagineDTO.getBase64())) {
			base64Array.add(imagineDTO.getBase64());
		}
		List<DataUrl> dataUrls;
		try {
			dataUrls = ConvertUtils.convertBase64Array(base64Array);
		} catch (MalformedURLException e) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "base64格式错误");
		}
		task.setPromptEn(promptEn);
		task.setDescription("/imagine " + prompt);
		return this.taskService.submitImagine(task, dataUrls);
	}

	@ApiOperation(value = "绘图变化-simple")
	@PostMapping("/simple-change")
	@CrossOrigin(origins = "*")
	public SubmitResultVO simpleChange(@RequestBody SubmitSimpleChangeDTO simpleChangeDTO) {
		TaskChangeParams changeParams = ConvertUtils.convertChangeParams(simpleChangeDTO.getContent());
		if (changeParams == null) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "content参数错误");
		}
		SubmitChangeDTO changeDTO = new SubmitChangeDTO();
		changeDTO.setAction(changeParams.getAction());
		changeDTO.setTaskId(changeParams.getId());
		changeDTO.setIndex(changeParams.getIndex());
		changeDTO.setState(simpleChangeDTO.getState());
		changeDTO.setNotifyHook(simpleChangeDTO.getNotifyHook());
		return change(changeDTO);
	}

	@ApiOperation(value = "绘图变化")
	@PostMapping("/change")
	@CrossOrigin(origins = "*")
	public SubmitResultVO change(@RequestBody SubmitChangeDTO changeDTO) {
		if (CharSequenceUtil.isBlank(changeDTO.getTaskId())) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "taskId不能为空");
		}
		if (!Set.of(TaskAction.UPSCALE, TaskAction.VARIATION, TaskAction.REROLL).contains(changeDTO.getAction())) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "action参数错误");
		}
		String description = "/up " + changeDTO.getTaskId();
		if (TaskAction.REROLL.equals(changeDTO.getAction())) {
			description += " R";
		} else {
			description += " " + changeDTO.getAction().name().charAt(0) + changeDTO.getIndex();
		}
		if (TaskAction.UPSCALE.equals(changeDTO.getAction())) {
			TaskCondition condition = new TaskCondition().setDescription(description);
			Task existTask = this.taskStoreService.findOne(condition);
			if (existTask != null) {
				return SubmitResultVO.of(ReturnCode.EXISTED, "任务已存在", existTask.getId())
						.setProperty("status", existTask.getStatus())
						.setProperty("imageUrl", existTask.getImageUrl());
			}
		}
		Task targetTask = this.taskStoreService.get(changeDTO.getTaskId());
		if (targetTask == null) {
			return SubmitResultVO.fail(ReturnCode.NOT_FOUND, "关联任务不存在或已失效");
		}
		if (!TaskStatus.SUCCESS.equals(targetTask.getStatus())) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "关联任务状态错误");
		}
		if (!Set.of(TaskAction.IMAGINE, TaskAction.VARIATION, TaskAction.REROLL, TaskAction.BLEND, TaskAction.DESCRIBE).contains(targetTask.getAction())) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "关联任务不允许执行变化");
		}
		Task task = newTask(changeDTO);
		task.setAction(changeDTO.getAction());
		task.setPrompt(targetTask.getPrompt());
		task.setPromptEn(targetTask.getPromptEn());
		task.setProperty(Constants.TASK_PROPERTY_FINAL_PROMPT, targetTask.getProperty(Constants.TASK_PROPERTY_FINAL_PROMPT));
		task.setProperty(Constants.TASK_PROPERTY_PROGRESS_MESSAGE_ID, targetTask.getProperty(Constants.TASK_PROPERTY_MESSAGE_ID));
		task.setProperty(Constants.TASK_PROPERTY_DISCORD_INSTANCE_ID, targetTask.getProperty(Constants.TASK_PROPERTY_DISCORD_INSTANCE_ID));

		if(targetTask.getDescription().startsWith("/describe")){
			description=targetTask.getDescription();
			int index= description.lastIndexOf(".");
			if(index>-1){
				description=description.substring(0,index);
			}
			task.setDescription(description+" R");
		}else{
			task.setDescription(description);
		}

		int messageFlags = targetTask.getPropertyGeneric(Constants.TASK_PROPERTY_FLAGS);
		String messageId = targetTask.getPropertyGeneric(Constants.TASK_PROPERTY_MESSAGE_ID);
		String messageHash = targetTask.getPropertyGeneric(Constants.TASK_PROPERTY_MESSAGE_HASH);
		if (TaskAction.UPSCALE.equals(changeDTO.getAction())) {
			return this.taskService.submitUpscale(task, messageId, messageHash, changeDTO.getIndex(), messageFlags);
		} else if (TaskAction.VARIATION.equals(changeDTO.getAction())) {
			return this.taskService.submitVariation(task, messageId, messageHash, changeDTO.getIndex(), messageFlags);
		} else {
			return this.taskService.submitReroll(task, messageId, messageHash, messageFlags);
		}
	}

	@ApiOperation(value = "提交Describe任务")
	@PostMapping("/describe")
	@CrossOrigin(origins = "*")
	public SubmitResultVO describe(@RequestBody SubmitDescribeDTO describeDTO) {
		if (CharSequenceUtil.isBlank(describeDTO.getBase64())) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "base64不能为空");
		}
		IDataUrlSerializer serializer = new DataUrlSerializer();
		DataUrl dataUrl;
		try {
			dataUrl = serializer.unserialize(describeDTO.getBase64());
		} catch (MalformedURLException e) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "base64格式错误");
		}
		Task task = newTask(describeDTO);
		task.setAction(TaskAction.DESCRIBE);
		String taskFileName = task.getId() + "." + MimeTypeUtils.guessFileSuffix(dataUrl.getMimeType());
		task.setDescription("/describe " + taskFileName);
		return this.taskService.submitDescribe(task, dataUrl);
	}

	@ApiOperation(value = "提交Blend任务")
	@PostMapping("/blend")
	@CrossOrigin(origins = "*")
	public SubmitResultVO blend(@RequestBody SubmitBlendDTO blendDTO) {
		List<String> base64Array = blendDTO.getBase64Array();
		if (base64Array == null || base64Array.size() < 2 || base64Array.size() > 5) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "base64List参数错误");
		}
		if (blendDTO.getDimensions() == null) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "dimensions参数错误");
		}
		IDataUrlSerializer serializer = new DataUrlSerializer();
		List<DataUrl> dataUrlList = new ArrayList<>();
		try {
			for (String base64 : base64Array) {
				DataUrl dataUrl = serializer.unserialize(base64);
				dataUrlList.add(dataUrl);
			}
		} catch (MalformedURLException e) {
			return SubmitResultVO.fail(ReturnCode.VALIDATION_ERROR, "base64格式错误");
		}
		Task task = newTask(blendDTO);
		task.setAction(TaskAction.BLEND);
		task.setDescription("/blend " + task.getId() + " " + dataUrlList.size());
		return this.taskService.submitBlend(task, dataUrlList, blendDTO.getDimensions());
	}

	private Task newTask(BaseSubmitDTO base) {
		Task task = new Task();
		task.setId(System.currentTimeMillis() + "" + RandomUtil.randomNumbers(3));
		task.setSubmitTime(System.currentTimeMillis());
		task.setState(base.getState());
		String notifyHook = CharSequenceUtil.isBlank(base.getNotifyHook()) ? this.properties.getNotifyHook() : base.getNotifyHook();
		task.setProperty(Constants.TASK_PROPERTY_NOTIFY_HOOK, notifyHook);
		task.setProperty(Constants.TASK_PROPERTY_NONCE, SnowFlake.INSTANCE.nextId());
		return task;
	}

	private String translatePrompt(String prompt) {
		String promptEn;

		promptEn = translatePromptEn(prompt);

		//新增对--no内容的翻译
		String[] prompts=promptEn.split(" --no ");

		String promptRT = prompts[0];
		if(prompts.length>=2){
			for(int i=1;i<prompts.length;i++){
				String promptTmp=prompts[i];
				String promptTmpEn=translatePromptEn(promptTmp);
				promptRT=promptRT+" --no "+promptTmpEn;
			}

		}else{
			promptRT=promptEn;
		}

		return promptRT;
	}

	@NotNull
	private String translatePromptEn(String prompt) {
		String promptEn;
		int paramStart = prompt.indexOf(" --");
		if (paramStart > 0) {
			promptEn = this.translateService.translateToEnglish(prompt.substring(0, paramStart)).trim() + prompt.substring(paramStart);
		} else {
			promptEn = this.translateService.translateToEnglish(prompt).trim();
		}
		if (CharSequenceUtil.isBlank(promptEn)) {
			promptEn = prompt;
		}
		return promptEn;
	}

}
