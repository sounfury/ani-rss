<template>
  <SettingsItem label="自动刮削">
    <el-switch v-model="props.config['scrape']"/>
  </SettingsItem>
  <SettingsItem label="115 本地刮削目录">
    <el-input v-model.trim="props.config['open115ScrapePath']" placeholder="/opt/media/strm/anime"/>
    <el-text class="mx-1" size="small">
      Open115 时 nfo、封面和 bangumi.ini 写到此目录下的相对保存路径，需与 SmartStrm 的 strm 根一致。
    </el-text>
  </SettingsItem>
  <SettingsItem label="115 等待 strm">
    <div>
      <el-input-number v-model="props.config['open115ScrapeDelay']" :min="0">
        <template #suffix>
          秒
        </template>
      </el-input-number>
      <br/>
      <el-text class="mx-1" size="small">
        下载完成通知 SmartStrm 后，等待 strm 落盘再刮集 nfo。0 表示不等待。
      </el-text>
    </div>
  </SettingsItem>
  <SettingsItem label="追更天数">
    <div>
      <el-input-number v-model="props.config['followDay']" :min="1">
        <template #suffix>
          天
        </template>
      </el-input-number>
      <br/>
      <el-text class="mx-1" size="small">
        自动强制刮削最近更新集的元数据
      </el-text>
    </div>
  </SettingsItem>
  <SettingsItem label="更多">
    <el-checkbox label="bangumi.ini" v-model="props.config['bangumiIniEnabled']"/>
  </SettingsItem>
  <SettingsItem label="TmdbApi">
    <el-input v-model:model-value="props.config['tmdbApi']" placeholder="https://api.themoviedb.org"/>
  </SettingsItem>
  <SettingsItem label="TmdbApiKey">
    <el-input v-model:model-value="props.config['tmdbApiKey']" placeholder="请自备 API 密钥, 留空使用系统默认"/>
  </SettingsItem>
  <SettingsItem label="TmdbImage">
    <el-input v-model:model-value="props.config['tmdbImage']" placeholder="https://image.tmdb.org"/>
  </SettingsItem>
</template>

<script setup>
import SettingsItem from "@/view/custom/SettingsItem.vue";

import {ElText} from "element-plus";

let props = defineProps(['config'])
</script>