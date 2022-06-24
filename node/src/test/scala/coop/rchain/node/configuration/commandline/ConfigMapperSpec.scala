package coop.rchain.node.configuration.commandline

import com.typesafe.config.ConfigFactory
import coop.rchain.casper.{CasperConf, GenesisBlockData}
import coop.rchain.casper.util.GenesisBuilder
import coop.rchain.comm.transport.TlsConf
import coop.rchain.comm.{CommError, PeerNode}
import coop.rchain.node.configuration._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import pureconfig._
import pureconfig.generic.auto._

import java.nio.file.Paths
import scala.concurrent.duration._

class ConfigMapperSpec extends AnyFunSuite with Matchers {

  test("CLI options should override defaults") {
    val args =
      Seq(
        "run",
        "--standalone",
        "--dev-mode",
        "--host localhost",
        "--bootstrap rnode://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.109?protocol=40400&discovery=40404",
        "--network-id testnet",
        "--no-upnp",
        "--dynamic-ip",
        "--autogen-shard-size 111111",
        "--use-random-ports",
        "--network-timeout 111111seconds",
        "--discovery-port 111111",
        "--discovery-lookup-interval 111111seconds",
        "--discovery-cleanup-interval 111111seconds",
        "--discovery-heartbeat-batch-size 111111",
        "--discovery-init-wait-loop-interval 111111seconds",
        "--protocol-port 111111",
        "--protocol-grpc-max-recv-message-size 111111",
        "--protocol-grpc-max-recv-stream-message-size 111111",
        "--protocol-grpc-stream-chunk-size 111111",
        "--protocol-max-connections 111111",
        "--protocol-max-message-consumers 111111",
        "--disable-state-exporter",
        //other vars?
        "--tls-certificate-path /var/lib/rnode/node.certificate.pem",
        "--tls-key-path /var/lib/rnode/node.key.pem",
        "--tls-secure-random-non-blocking",
        "--api-host localhost",
        "--api-port-grpc-external 111111",
        "--api-port-grpc-internal 111111",
        "--api-port-http 111111",
        "--api-port-admin-http 111111",
        "--api-grpc-max-recv-message-size 111111",
        "--api-max-blocks-limit 111111",
        "--api-enable-reporting",
        "--api-keep-alive-time 111111seconds",
        "--api-keep-alive-timeout 111111seconds",
        "--api-permit-keep-alive-time 111111seconds",
        "--api-max-connection-idle 111111seconds",
        "--api-max-connection-age 111111seconds",
        "--api-max-connection-age-grace 111111seconds",
        "--data-dir /var/lib/rnode",
        // other vars?
        "--shard-name root",
        "--validator-public-key 111111",
        "--validator-private-key 111111",
        "--validator-private-key-path /var/lib/rnode/pem.key",
        "--casper-loop-interval 111111seconds",
        "--requested-blocks-timeout 111111seconds",
        "--max-number-of-parents 111111",
        "--fork-choice-stale-threshold 111111seconds",
        "--fork-choice-check-if-stale-interval 111111seconds",
        "--synchrony-constraint-threshold 111111",
        "--height-constraint-threshold 111111",
        "--bonds-file /var/lib/rnode/genesis/bonds1.txt",
        "--wallets-file /var/lib/rnode/genesis/wallets1.txt",
        "--bond-minimum 111111",
        "--bond-maximum 111111",
        "--epoch-length 111111",
        "--quarantine-length 111111",
        "--genesis-block-number 222",
        "--number-of-active-validators 111111",
        "--pos-vault-pub-key 0432946f7f91f8f767d7c3d43674faf83586dffbd1b8f9278a5c72820dc20308836299f47575ff27f4a736b72e63d91c3cd853641861f64e08ee5f9204fc708df6",
        "--system-contract-pub-key 04e2eb6b06058d10b30856043c29076e2d2d7c374d2beedded6ecb8d1df585dfa583bd7949085ac6b0761497b0cfd056eb3d0db97efb3940b14c00fff4e53c85bf",
        "--disable-lfs",
        "--prometheus",
        "--influxdb",
        "--influxdb-udp",
        "--zipkin",
        "--sigar"
      ).mkString(" ")

    val options = Options(args.split(' '))

    val defaultConfig = ConfigSource
      .resources("defaults.conf")
      .withFallback(
        ConfigSource.string(
          s"default-data-dir = /var/lib/rnode"
        )
      )

    // Custom reader for PeerNode type
    def commErrToThrow(commErr: CommError) =
      new Exception(CommError.errorMessage(commErr))

    implicit val peerNodeReader = ConfigReader.fromStringTry[PeerNode](
      PeerNode.fromAddress(_).left.map(commErrToThrow).toTry
    )

    // Make Long values support size-in-bytes format, e.g. 16M
    implicit val myIntReader = ConfigReader.fromString[Long](
      ConvertHelpers.catchReadError(s => ConfigFactory.parseString(s"v = $s").getBytes("v"))
    )
    val config = ConfigSource
      .fromConfig(ConfigMapper.fromOptions(options))
      .withFallback(defaultConfig)
      .load[NodeConf]
      .right
      .get

    val expectedConfig = NodeConf(
      defaultDataDir = "/var/lib/rnode",
      standalone = true,
      autopropose = false,
      devMode = true,
      protocolServer = ProtocolServer(
        networkId = "testnet",
        host = Some("localhost"),
        useRandomPorts = true,
        dynamicIp = true,
        noUpnp = true,
        port = 111111,
        grpcMaxRecvMessageSize = 111111,
        grpcMaxRecvStreamMessageSize = 111111,
        maxMessageConsumers = 111111,
        disableStateExporter = true
      ),
      protocolClient = ProtocolClient(
        networkId = "testnet",
        bootstrap = PeerNode
          .fromAddress(
            "rnode://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.109?protocol=40400&discovery=40404"
          )
          .right
          .get,
        disableLfs = true,
        batchMaxConnections = 111111,
        networkTimeout = 111111.seconds,
        grpcMaxRecvMessageSize = 111111,
        grpcStreamChunkSize = 111111
      ),
      peersDiscovery = PeersDiscovery(
        port = 111111,
        lookupInterval = 111111.seconds,
        cleanupInterval = 111111.seconds,
        heartbeatBatchSize = 111111,
        initWaitLoopInterval = 111111.seconds
      ),
      apiServer = ApiServer(
        host = "localhost",
        portGrpcExternal = 111111,
        portGrpcInternal = 111111,
        portHttp = 111111,
        portAdminHttp = 111111,
        grpcMaxRecvMessageSize = 111111,
        maxBlocksLimit = 111111,
        enableReporting = true,
        keepAliveTime = 111111L.seconds,
        keepAliveTimeout = 111111L.seconds,
        permitKeepAliveTime = 111111L.seconds,
        maxConnectionAge = 111111L.seconds,
        maxConnectionIdle = 111111L.seconds,
        maxConnectionAgeGrace = 111111L.seconds
      ),
      storage = Storage(
        dataDir = Paths.get("/var/lib/rnode")
      ),
      tls = TlsConf(
        certificatePath = Paths.get("/var/lib/rnode/node.certificate.pem"),
        keyPath = Paths.get("/var/lib/rnode/node.key.pem"),
        secureRandomNonBlocking = true,
        customCertificateLocation = false,
        customKeyLocation = false
      ),
      casper = CasperConf(
        validatorPublicKey = Some("111111"),
        validatorPrivateKey = Some("111111"),
        validatorPrivateKeyPath = Some(Paths.get("/var/lib/rnode/pem.key")),
        shardName = "root",
        casperLoopInterval = 111111.seconds,
        requestedBlocksTimeout = 111111.seconds,
        maxNumberOfParents = 111111,
        forkChoiceStaleThreshold = 111111.seconds,
        forkChoiceCheckIfStaleInterval = 111111.seconds,
        synchronyConstraintThreshold = 111111,
        heightConstraintThreshold = 111111,
        genesisBlockData = GenesisBlockData(
          genesisDataDir = Paths.get("/var/lib/rnode/genesis"),
          bondsFile = "/var/lib/rnode/genesis/bonds1.txt",
          walletsFile = "/var/lib/rnode/genesis/wallets1.txt",
          bondMaximum = 111111,
          bondMinimum = 111111,
          epochLength = 111111,
          quarantineLength = 111111,
          numberOfActiveValidators = 111111,
          genesisBlockNumber = 222,
          posMultiSigPublicKeys = GenesisBuilder.defaultPosMultiSigPublicKeys,
          posMultiSigQuorum = GenesisBuilder.defaultPosMultiSigPublicKeys.length - 1,
          posVaultPubKey = GenesisBuilder.defaultPosVaultPubKey,
          systemContractPubKey = GenesisBuilder.defaultSystemContractPubKey
        ),
        autogenShardSize = 111111,
        minPhloPrice = 1
      ),
      metrics = Metrics(
        prometheus = true,
        influxdb = true,
        influxdbUdp = true,
        zipkin = true,
        sigar = true
      ),
      dev = DevConf(deployerPrivateKey = None)
    )
    config shouldEqual expectedConfig
  }
}
