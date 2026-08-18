 require("@nomicfoundation/hardhat-toolbox");
 require("dotenv").config();
 
 /** @type import('hardhat/config').HardhatUserConfig */
 module.exports = {
   solidity: {
     version: "0.8.26",
     settings: {
       optimizer: {
         enabled: true,
         runs: 200,
       },
       viaIR: true,
       evmVersion: "cancun",
     },
   },
   networks: {
     hardhat: {
       chainId: 31337,
     },
     localhost: {
       url: "http://127.0.0.1:8545",
       chainId: 31337,
     },
     fisco: {
       url: process.env.FISCO_RPC_URL || "http://127.0.0.1:8545",
       accounts: process.env.PRIVATE_KEY ? [process.env.PRIVATE_KEY] : [],
     },
   },
   paths: {
     sources: "./contracts",
     tests: "./test",
     cache: "./cache",
     artifacts: "./artifacts",
   },
 };
