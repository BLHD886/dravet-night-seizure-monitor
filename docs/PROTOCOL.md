# 协议范围

项目测试设备JX630 / MOYOUNG-V2。按UUID发现，FEEA服务，FEE2无响应写，FEE3通知。六字节帧FE EA 20 06 6B 00启动血氧，6D 00启动脉搏；FF为停止/结束状态，不是数值。脉搏也通过标准180D/2A37接收。支持碎片与合并帧；完整代码见BleWatch.java和OxygenCodec.java。

血氧与脉搏不能并行，停止旧测量后再启动目标；FF后继续等本轮90秒接收窗口。以上是特定设备工程适配，不保证其他固件兼容。没有复制Gadgetbridge代码；其社区协议文档可作事实参考：https://gadgetbridge.org/internals/specifics/moyoung-protocol/ 。

LD6002当前仅UI合成演示；prototype/ld6004.py只解析LD6004的区域/目标/点云协议，不提供LD6002呼吸数据。
