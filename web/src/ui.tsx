import { Alert, Button, Spin } from 'antd'

export function Loading({ text = '正在加载' }: { text?: string }) {
  return <div className="feedback" role="status"><Spin size="small" />{text}</div>
}

export function Failure({ message, retry }: { message: string; retry?: () => void }) {
  return <Alert type="error" showIcon message={message}
    action={retry && <Button size="small" onClick={retry}>重试</Button>} />
}

export function Brand() {
  return <div className="brand">
    <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
    <span>采购到货核对工作台</span>
  </div>
}
