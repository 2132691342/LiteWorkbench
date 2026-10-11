import {
  Brush,
  ChatDotRound,
  Coin,
  Document,
  Files,
  Grid,
  List,
  Lock,
  MagicStick,
  Odometer,
  OfficeBuilding,
  Setting,
  User,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

/** 后端 menu.icon 到 Element Plus 图标的映射，未知图标回退为 Grid */
export const MENU_ICONS: Record<string, Component> = {
  dashboard: Grid,
  chat: ChatDotRound,
  console: ChatDotRound,
  folder: Files,
  bulb: MagicStick,
  document: Document,
  pulse: Odometer,
  palette: Brush,
  gear: Setting,
  setting: Setting,
  user: User,
  shield: Lock,
  list: List,
  building: OfficeBuilding,
  coin: Coin,
}

/** 按名称取图标组件 */
export function menuIcon(name?: string): Component {
  if (!name) {
    return Grid
  }
  return MENU_ICONS[name] ?? Grid
}
