export const FIXED_ORDER_NAME = '产片固定顺序'

const BASE_STAGES = [
  ['开头钩子', ['钩子', '开场', 'hook']],
  ['痛点场景', ['痛点', '场景', '问题']],
  ['真实反应', ['反应', '试用', '达人']],
  ['原理说明', ['原理', '机制', '成分']],
  ['产品展示', ['产品', '包装', '商品']],
  ['使用过程', ['使用', '过程', '功效']],
  ['购买理由', ['卖点', '优惠', '规格']],
  ['行动收尾', ['收尾', '片尾', '下单']]
]

function stages(names = BASE_STAGES.map(([name]) => name), keywords = BASE_STAGES.map(([, words]) => words)) {
  return names.map((name, index) => ({
    order: index + 1,
    name,
    folderId: null,
    fallbackFolderId: null,
    required: true,
    enabled: true,
    targetSec: 6,
    aiSelect: true,
    shortagePolicy: 'block',
    folderKeywords: keywords[index] || []
  }))
}

export const FIXED_ORDER_PRESETS = [
  { key: 'beauty-skincare', name: '美妆护肤带货', description: '肤感、成分、上脸效果和购买理由优先。', stages: stages() },
  { key: 'food-beverage', name: '食品饮料种草', description: '口感痛点、真实试吃、配料与促单更突出。', stages: stages(['开场食欲', '消费痛点', '真实试吃', '配料/工艺', '产品特写', '食用场景', '规格优惠', '立即下单'], [['开场', '食欲', 'hook'], ['痛点', '场景'], ['试吃', '反应'], ['配料', '工艺'], ['产品', '包装'], ['食用', '过程'], ['规格', '优惠'], ['下单', '收尾']]) },
  { key: 'maternal-parenting', name: '母婴育儿好物', description: '安全、照护场景、宝宝反应和家长决策信息优先。', stages: stages(['育儿钩子', '照护痛点', '宝宝/家长反应', '安全原理', '产品展示', '使用过程', '适用与优惠', '安心下单'], [['育儿', '开场'], ['照护', '痛点'], ['宝宝', '反应'], ['安全', '原理'], ['产品', '包装'], ['使用', '过程'], ['适用', '优惠'], ['下单', '收尾']]) },
  { key: 'digital-tech', name: '3C数码开箱', description: '问题切入、功能验证、细节展示与参数决策优先。', stages: stages(['结果钩子', '使用痛点', '上手反应', '功能原理', '开箱展示', '实测过程', '参数卖点', '购买收尾'], [['结果', '开场'], ['痛点', '问题'], ['上手', '反应'], ['功能', '原理'], ['开箱', '产品'], ['实测', '使用'], ['参数', '卖点'], ['购买', '收尾']]) },
  { key: 'fashion-apparel', name: '服饰穿搭种草', description: '身材痛点、试穿反应、搭配过程和风格卖点优先。', stages: stages(['穿搭钩子', '穿着痛点', '试穿反应', '版型原理', '单品展示', '搭配过程', '风格优惠', '下单收尾'], [['穿搭', '开场'], ['穿着', '痛点'], ['试穿', '反应'], ['版型', '原理'], ['单品', '产品'], ['搭配', '使用'], ['风格', '优惠'], ['下单', '收尾']]) },
  { key: 'home-daily', name: '家居日用好物', description: '生活麻烦、解决机制、前后对比和性价比优先。', stages: stages(['生活钩子', '家务痛点', '真实反应', '解决原理', '好物展示', '使用对比', '省心卖点', '立即购买'], [['生活', '开场'], ['家务', '痛点'], ['反应', '体验'], ['解决', '原理'], ['好物', '产品'], ['使用', '对比'], ['省心', '卖点'], ['购买', '收尾']]) },
  { key: 'knowledge-explainer', name: '知识口播讲解', description: '问题提出、原理解释、案例证明和行动建议优先。', stages: stages(['问题开场', '现象痛点', '案例反应', '知识原理', '工具展示', '操作步骤', '重点总结', '行动建议'], [['问题', '开场'], ['现象', '痛点'], ['案例', '反应'], ['知识', '原理'], ['工具', '产品'], ['操作', '步骤'], ['总结', '重点'], ['建议', '收尾']]) }
]

export function cloneFixedOrderPreset (preset) {
  const source = typeof preset === 'string'
    ? FIXED_ORDER_PRESETS.find((item) => item.key === preset)
    : preset
  if (!source) return null
  return {
    key: source.key,
    name: source.name,
    description: source.description,
    stages: source.stages.map((stage) => ({ ...stage, folderKeywords: [...(stage.folderKeywords || [])] }))
  }
}
