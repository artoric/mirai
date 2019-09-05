package net.mamoe.mirai.network

import net.mamoe.mirai.Robot
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.QQ
import net.mamoe.mirai.event.events.qq.FriendMessageEvent
import net.mamoe.mirai.event.events.robot.RobotLoginSucceedEvent
import net.mamoe.mirai.message.Message
import net.mamoe.mirai.network.packet.*
import net.mamoe.mirai.network.packet.action.ServerSendFriendMessageResponsePacket
import net.mamoe.mirai.network.packet.action.ServerSendGroupMessageResponsePacket
import net.mamoe.mirai.network.packet.login.*
import net.mamoe.mirai.task.MiraiThreadPool
import net.mamoe.mirai.utils.*
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * A RobotNetworkHandler is used to connect with Tencent servers.
 *
 * @author Him188moe
 */
@Suppress("EXPERIMENTAL_API_USAGE")//to simplify code
internal class RobotNetworkHandler(private val robot: Robot) : Closeable {
    private val socketHandler: SocketHandler = SocketHandler()

    val debugHandler = DebugHandler()
    val loginHandler = LoginHandler()
    val messageHandler = MessageHandler()
    val actionHandler = ActionHandler()

    private val packetHandlers: Map<KClass<out PacketHandler>, PacketHandler> = mapOf(
            DebugHandler::class to debugHandler,
            LoginHandler::class to loginHandler,
            MessageHandler::class to messageHandler,
            ActionHandler::class to actionHandler
    )

    private var closed: Boolean = false


    /**
     * Not async
     */
    @ExperimentalUnsignedTypes
    fun sendPacket(packet: ClientPacket) {
        socketHandler.sendPacket(packet)
    }

    override fun close() {
        this.packetHandlers.values.forEach {
            it.close()
        }
        this.socketHandler.close()
    }


    //private | internal

    internal fun tryLogin(loginHook: ((LoginState) -> Unit)? = null) {
        val ipQueue: LinkedList<String> = LinkedList(Protocol.SERVER_IP)
        fun login(): Boolean {
            val ip = ipQueue.poll()
            return if (ip != null) {
                this@RobotNetworkHandler.socketHandler.touch(ip) { state ->
                    if (state == LoginState.UNKNOWN) {
                        login()
                    } else {
                        loginHook?.invoke(state)
                    }
                }
                true
            } else false
        }
        login()
    }

    @ExperimentalUnsignedTypes
    internal fun onPacketReceived(packet: ServerPacket) {
        this.packetHandlers.values.forEach {
            it.onPacketReceived(packet)
        }
    }


    private inner class SocketHandler : Closeable {
        private lateinit var socket: DatagramSocket

        internal var serverIP: String = ""
            set(value) {
                serverAddress = InetSocketAddress(value, 8000)
                field = value

                restartSocket()
            }

        private var loginHook: ((LoginState) -> Unit)? = null
        internal var loginState: LoginState? = null
            set(value) {
                field = value
                if (value != null && value != LoginState.UNKNOWN) {
                    loginHook?.invoke(value)
                }
            }

        private lateinit var serverAddress: InetSocketAddress

        private fun restartSocket() {

            socket = DatagramSocket((15314 + Math.random() * 100).toInt())
            socket.close()
            socket.connect(this.serverAddress)
            Thread {
                while (socket.isConnected) {
                    val packet = DatagramPacket(ByteArray(2048), 2048)
                    kotlin
                            .runCatching { socket.receive(packet) }
                            .onSuccess {
                                MiraiThreadPool.getInstance().submit {
                                    try {
                                        onPacketReceived(ServerPacket.ofByteArray(packet.data.removeZeroTail()))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }.onFailure {
                                if (it.message == "socket closed") {
                                    if (!closed) {
                                        restartSocket()
                                    }
                                    return@Thread
                                }
                                it.printStackTrace()
                            }

                }
            }.start()
        }

        /**
         * Start network and touch the server
         */
        internal fun touch(serverAddress: String, loginHook: ((LoginState) -> Unit)? = null) {
            socketHandler.serverIP = serverAddress
            if (loginHook != null) {
                this.loginHook = loginHook
            }
            sendPacket(ClientTouchPacket(robot.account.qqNumber, socketHandler.serverIP))
        }

        /**
         * Not async
         */
        @ExperimentalUnsignedTypes
        internal fun sendPacket(packet: ClientPacket) {
            try {
                packet.encode()
                packet.writeHex(Protocol.tail)

                val data = packet.toByteArray()
                socket.send(DatagramPacket(data, data.size))
                MiraiLogger info "Packet sent: $packet"
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        override fun close() {
            this.socket.close()
            this.loginState = null
            this.loginHook = null
        }
    }


    private lateinit var sessionKey: ByteArray

    abstract inner class PacketHandler : Closeable {
        abstract fun onPacketReceived(packet: ServerPacket)

        override fun close() {

        }
    }

    /**
     * Kind of [PacketHandler] that prints all packets received in the format of hex byte array.
     */
    inner class DebugHandler : PacketHandler() {
        override fun onPacketReceived(packet: ServerPacket) {
            packet.decode()
            MiraiLogger info "Packet received: $packet"
            if (packet is ServerEventPacket) {
                sendPacket(ClientMessageResponsePacket(robot.account.qqNumber, packet.packetId, sessionKey, packet.eventIdentity))
            }
        }
    }

    /**
     * 处理登录过程
     */
    inner class LoginHandler : PacketHandler() {
        private lateinit var token00BA: ByteArray
        private lateinit var token0825: ByteArray
        private var loginTime: Int = 0
        private lateinit var loginIP: String
        private var tgtgtKey: ByteArray? = null

        private var tlv0105: ByteArray = lazyEncode {
            it.writeHex("01 05 00 30")
            it.writeHex("00 01 01 02 00 14 01 01 00 10")
            it.writeRandom(16)
            it.writeHex("00 14 01 02 00 10")
            it.writeRandom(16)
        }

        /**
         * 0828_decr_key
         */
        private lateinit var sessionResponseDecryptionKey: ByteArray

        private var verificationCodeSequence: Int = 0//这两个验证码使用
        private var verificationCodeCache: ByteArray? = null//每次包只发一部分验证码来
        private var verificationCodeCacheCount: Int = 1//
        private lateinit var verificationToken: ByteArray


        private var heartbeatFuture: ScheduledFuture<*>? = null
        private var sKeyRefresherFuture: ScheduledFuture<*>? = null

        override fun onPacketReceived(packet: ServerPacket) {
            when (packet) {
                is ServerTouchResponsePacket -> {
                    if (packet.serverIP != null) {//redirection
                        socketHandler.serverIP = packet.serverIP!!
                        //connect(packet.serverIP!!)
                        sendPacket(ClientServerRedirectionPacket(packet.serverIP!!, robot.account.qqNumber))
                    } else {//password submission
                        this.loginIP = packet.loginIP
                        this.loginTime = packet.loginTime
                        this.token0825 = packet.token0825
                        this.tgtgtKey = packet.tgtgtKey
                        sendPacket(ClientPasswordSubmissionPacket(robot.account.qqNumber, robot.account.password, packet.loginTime, packet.loginIP, packet.tgtgtKey, packet.token0825))
                    }
                }

                is ServerLoginResponseFailedPacket -> {
                    socketHandler.loginState = packet.loginState
                    MiraiLogger error "Login failed: " + packet.loginState.toString()
                    return
                }

                is ServerLoginResponseVerificationCodeInitPacket -> {
                    //[token00BA]来源之一: 验证码
                    this.token00BA = packet.token00BA
                    this.verificationCodeCache = packet.verifyCodePart1

                    if (packet.unknownBoolean != null && packet.unknownBoolean!!) {
                        this.verificationCodeSequence = 1
                        sendPacket(ClientVerificationCodeTransmissionRequestPacket(1, robot.account.qqNumber, this.token0825, this.verificationCodeSequence, this.token00BA))
                    }
                }

                is ServerVerificationCodeRepeatPacket -> {//todo 这个名字正确么
                    this.tgtgtKey = packet.tgtgtKeyUpdate
                    this.token00BA = packet.token00BA
                    sendPacket(ClientLoginResendPacket3105(robot.account.qqNumber, robot.account.password, this.loginTime, this.loginIP, this.tgtgtKey!!, this.token0825, this.token00BA))
                }

                is ServerVerificationCodeTransmissionPacket -> {
                    this.verificationCodeSequence++
                    this.verificationCodeCache = this.verificationCodeCache!! + packet.verificationCodePartN

                    this.verificationToken = packet.verificationToken
                    this.verificationCodeCacheCount++

                    this.token00BA = packet.token00BA


                    //todo 看易语言 count 和 sequence 是怎样变化的

                    if (packet.transmissionCompleted) {
                        this.verificationCodeCache
                        TODO("验证码好了")
                    } else {
                        sendPacket(ClientVerificationCodeTransmissionRequestPacket(this.verificationCodeCacheCount, robot.account.qqNumber, this.token0825, this.verificationCodeSequence, this.token00BA))
                    }
                }

                is ServerLoginResponseSuccessPacket -> {
                    this.sessionResponseDecryptionKey = packet.sessionResponseDecryptionKey
                    sendPacket(ClientSessionRequestPacket(robot.account.qqNumber, socketHandler.serverIP, packet.token38, packet.token88, packet.encryptionKey, this.tlv0105))
                }

                //是ClientPasswordSubmissionPacket之后服务器回复的
                is ServerLoginResponseResendPacket -> {
                    //if (packet.tokenUnknown != null) {
                    //this.token00BA = packet.token00BA!!
                    //println("token00BA changed!!! to " + token00BA.toUByteArray())
                    //}
                    if (packet.flag == ServerLoginResponseResendPacket.Flag.`08 36 31 03`) {
                        this.tgtgtKey = packet.tgtgtKey
                        sendPacket(ClientLoginResendPacket3104(
                                robot.account.qqNumber,
                                robot.account.password,
                                this.loginTime,
                                this.loginIP,
                                this.tgtgtKey!!,
                                this.token0825,
                                when (packet.tokenUnknown != null) {
                                    true -> packet.tokenUnknown!!
                                    false -> this.token00BA
                                },
                                packet._0836_tlv0006_encr
                        ))
                    } else {
                        sendPacket(ClientLoginResendPacket3106(
                                robot.account.qqNumber,
                                robot.account.password,
                                this.loginTime,
                                this.loginIP,
                                this.tgtgtKey!!,
                                this.token0825,
                                when (packet.tokenUnknown != null) {
                                    true -> packet.tokenUnknown!!
                                    false -> this.token00BA
                                },
                                packet._0836_tlv0006_encr
                        ))
                    }
                }

                is ServerSessionKeyResponsePacket -> {
                    sessionKey = packet.sessionKey
                    heartbeatFuture = MiraiThreadPool.getInstance().scheduleWithFixedDelay({
                        sendPacket(ClientHeartbeatPacket(robot.account.qqNumber, sessionKey))
                    }, 90000, 90000, TimeUnit.MILLISECONDS)

                    RobotLoginSucceedEvent(robot).broadcast()

                    //登录成功后会收到大量上次的消息, 忽略掉
                    MiraiThreadPool.getInstance().schedule({
                        (packetHandlers[MessageHandler::class] as MessageHandler).ignoreMessage = false
                    }, 2, TimeUnit.SECONDS)

                    this.tlv0105 = packet.tlv0105
                    sendPacket(ClientChangeOnlineStatusPacket(robot.account.qqNumber, sessionKey, ClientLoginStatus.ONLINE))
                }

                is ServerLoginSuccessPacket -> {
                    socketHandler.loginState = LoginState.SUCCEED
                    sendPacket(ClientSKeyRequestPacket(robot.account.qqNumber, sessionKey))
                }

                is ServerSKeyResponsePacket -> {
                    val actionHandler = packetHandlers[ActionHandler::class] as ActionHandler
                    actionHandler.sKey = packet.sKey
                    actionHandler.cookies = "uin=o" + robot.account.qqNumber + ";skey=" + actionHandler.sKey + ";"

                    sKeyRefresherFuture = MiraiThreadPool.getInstance().scheduleWithFixedDelay({
                        sendPacket(ClientSKeyRefreshmentRequestPacket(robot.account.qqNumber, sessionKey))
                    }, 1800000, 1800000, TimeUnit.MILLISECONDS)

                    actionHandler.gtk = getGTK(actionHandler.sKey)
                    sendPacket(ClientAccountInfoRequestPacket(robot.account.qqNumber, sessionKey))
                }

                is ServerEventPacket.Raw -> onPacketReceived(packet.distribute())

                is ServerVerificationCodePacket.Encrypted -> onPacketReceived(packet.decrypt())
                is ServerLoginResponseVerificationCodeInitPacket.Encrypted -> onPacketReceived(packet.decrypt())
                is ServerLoginResponseResendPacket.Encrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
                is ServerLoginResponseSuccessPacket.Encrypted -> onPacketReceived(packet.decrypt(this.tgtgtKey!!))
                is ServerSessionKeyResponsePacket.Encrypted -> onPacketReceived(packet.decrypt(this.sessionResponseDecryptionKey))
                is ServerTouchResponsePacket.Encrypted -> onPacketReceived(packet.decrypt())
                is ServerSKeyResponsePacket.Encrypted -> onPacketReceived(packet.decrypt(sessionKey))
                is ServerAccountInfoResponsePacket.Encrypted -> onPacketReceived(packet.decrypt(sessionKey))
                is ServerEventPacket.Raw.Encrypted -> onPacketReceived(packet.decrypt(sessionKey))


                is ServerAccountInfoResponsePacket,
                is ServerHeartbeatResponsePacket,
                is UnknownServerPacket -> {
                    //ignored
                }
                else -> {

                }
            }
        }

        override fun close() {
            this.verificationCodeCache = null
            this.tgtgtKey = null

            this.heartbeatFuture?.cancel(true)
            this.sKeyRefresherFuture?.cancel(true)

            this.heartbeatFuture = null
            this.sKeyRefresherFuture = null
        }
    }

    /**
     * 处理消息事件, 承担消息发送任务.
     */
    inner class MessageHandler : PacketHandler() {
        internal var ignoreMessage: Boolean = false

        override fun onPacketReceived(packet: ServerPacket) {
            when (packet) {
                is ServerGroupUploadFileEventPacket -> {
                    //todo
                }

                is ServerFriendMessageEventPacket -> {
                    if (ignoreMessage) {
                        return
                    }

                    FriendMessageEvent(robot, robot.contacts.getQQ(packet.qq), packet.message)
                }

                is ServerGroupMessageEventPacket -> {
                    //todo message chain
                    //GroupMessageEvent(this.robot, robot.contacts.getGroupByNumber(packet.groupNumber), robot.contacts.getQQ(packet.qq), packet.message)
                }

                is UnknownServerEventPacket,
                is ServerSendFriendMessageResponsePacket,
                is ServerSendGroupMessageResponsePacket -> {
                    //ignored
                }
                else -> {
                    //ignored
                }
            }
        }

        fun sendFriendMessage(qq: QQ, message: Message) {
            TODO()
            //sendPacket(ClientSendFriendMessagePacket(robot.account.qqNumber, qq.number, sessionKey, message))
        }

        fun sendGroupMessage(group: Group, message: Message): Unit {
            TODO()
            //sendPacket(ClientSendGroupMessagePacket(group.groupId, robot.account.qqNumber, sessionKey, message))
        }
    }

    /**
     * 动作: 获取好友列表, 点赞, 踢人等.
     * 处理动作事件, 承担动作任务.
     */
    inner class ActionHandler : PacketHandler() {
        internal lateinit var cookies: String
        internal lateinit var sKey: String
        internal var gtk: Int = 0

        override fun onPacketReceived(packet: ServerPacket) {

        }

        override fun close() {

        }
    }
}