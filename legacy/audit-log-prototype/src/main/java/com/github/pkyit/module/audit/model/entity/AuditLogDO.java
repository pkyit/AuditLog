package com.github.pkyit.module.audit.model.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 审计日志数据实体类对象
 *
 */
@Data
@AllArgsConstructor
public class AuditLogDO implements Serializable {
	private static final long serialVersionUID = 26361727641579016L;

	/**
	 * 审计日志主键id
	 *
	 */
	private Long id;

	/**
	 * 用户id
	 *
	 */
	private String userId;

	/**
	 * 操作名称
	 *
	 */
	private String operationName;

	/**
	 * 用户端IP地址
	 *
	 */
	private String clientIp;

	/**
	 * 请求URI
	 *
	 */
	private String requestUri;

	/**
	 * 请求类型
	 *
	 */
	private String requestType;

	/**
	 * 请求体参数
	 *
	 */
	private String requestParams;

	/**
	 * 请求头
	 *
	 */
	private String requestHeaders;

	/**
	 * 响应体参数
	 *
	 */
	private String responseParams;

	/**
	 * 响应状态码
	 *
	 */
	private Integer responseCode;

	/**
	 * 操作耗时，单位毫秒
	 *
	 */
	private Long duration;

	/**
	 * 报错信息，如果无错误则返回空字符串
	 *
	 */
	private String exceptionInfo;

	/**
	 * 创建时间
	 */
	private Date createTime;
}
