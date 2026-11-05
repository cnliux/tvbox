package com.tvbox.app.domain

/** 单条节目（XMLTV programme 节点）。 */
data class EpgProgram(val title: String, val startMs: Long, val stopMs: Long)
