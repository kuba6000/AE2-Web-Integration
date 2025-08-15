<div align=center>

# AE2 Web Integration

[![CF Downloads](https://cf.way2muchnoise.eu/full_1122761_downloads.svg)](https://www.curseforge.com/minecraft/mc-mods/ae2-web-integration) [![Modrinth Downloads](https://img.shields.io/modrinth/dt/8MGTfmHF?logo=modrinth)
](https://modrinth.com/mod/mobsinfo)

</div>

An add-on for AE2 that lets you view your terminal in your web browser wherever you are! This also includes viewing orders, cancelling them and even making a new ones!   

**Now also works on multiple networks and on public server = all players can have their own account!**   

**THE MOD SHOULD ONLY BE INSTALLED ON SERVER SIDE, THERE IS NO ITEMS ADDED IN THE GAME!**

# How?

This mod starts a web server on server boot that hosts a simple website that you can access directly, or through API calls

# Current features

- AE2 terminal (item list+sorting+filtering)
- **Monitor as many networks as you want!**
- **Public mode, that allows the mod to works on servers with many independent players!**
- Start a new order
- Check any CPU status
- Cancel any CPU order
- Order tracking

# Gallery

<details>
<summary>Gallery</summary>

<img width="2560" height="1440" alt="image" src="https://github.com/user-attachments/assets/9363c6c4-26dd-46fe-a6c2-84111338e6b0" />
<img width="737" height="70" alt="image" src="https://github.com/user-attachments/assets/2d95024e-25ca-415f-a63d-945f2c906302" />
<img width="2560" height="1440" alt="image" src="https://github.com/user-attachments/assets/c880a117-75d3-4d53-9ebf-db67135a3275" />
<img width="2560" height="1440" alt="image" src="https://github.com/user-attachments/assets/2aeb72af-7abf-4cad-9a7a-1f66bd243594" />

</details>

# Security

## In public mode,

all users on your server can create an account on the website and access their AE2 networks,  
passwords are saved on the server as a PBKDF2WithHmacSHA1 hash  
Once a user is authenticated, server generates a token valid for 1 hour/7 days and uses it to verify all requests after  

## In public mode disabled,
 there is only an admin account which password is set in the config.
token mechanism is the same as in public mode.

**Note: localhost connections are automatically authenticated by default, you can change that in config!**

# Requirements

- An open port if you want to access the service outside your local network (configurable, terminal will be hosted at http://your-server-ip-or-domain:configured-port/ for example: http://server.kuba6000.pl:2324/)

# How to use

- Download the latest version for your game version from the releases page
- Drop the mod in your server mods folder (only on the server!) (This also works on single player, but is not recommended)
- Start the server
- You can now find the config in /configs/ae2webintegration/ae2webintegration.cfg. Edit the port number and password protection for your needs
- **Disable public mode if you play by yourself**
- Reload the config (/ae2webintegration reload) or restart the server
- Make sure you have opened the configured port (firewall, redirections) if you want to use it on public internet
- Now you can visit http://your-server-ip-or-domain:configured-port/ and login should appear on your browser!
- **There is a default user Admin which password is set in the config**

# Discord integration

**Note: Discord integration is working only in public mode disabled!**
- Create a webhook on your discord server and set it in the ae2webintegration config
- <img width="467" height="224" alt="image" src="https://github.com/user-attachments/assets/f9f7635d-676c-40a3-8334-f7fa35e5867a" />


# Custom website

- If you already have a web server and want to host the panel there, you can!
- There is currently no API documentation...
- Check out [Simple proxy site](https://github.com/kuba6000/AE2-Web-Integration/tree/master/example_website) !
- There you can find a simple website written in PHP ready to use, it's just simple proxy to API calls to the AE2 endpoint.

# Compatibility

The mod is currently implemented only on 1.7.10 and 1.12.2 versions, although it might change in the future!   
A few remarks about compatibility:
- 1.7.10 version is based on [GTNH fork of AE2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial) and [GTNH fork AE2FC for 1.7.10](https://github.com/GTNewHorizons/AE2FluidCraft-Rework)
- 1.12.2 version is based on [AE2-UEL](https://github.com/AE2-UEL/Applied-Energistics-2) and [AE2FC for 1.12.2](https://github.com/AE2-UEL/AE2FluidCraft-Rework/)
