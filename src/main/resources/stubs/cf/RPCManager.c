// Community Framework stub, bundled with the Enforce Script plugin so GetRPCManager(), CallType
// and RPCManager resolve without a real @CF checkout on disk -- see EnfusionLibraryRootsProvider.
//
// Signatures are taken verbatim from the real source at
// JM/CF/Scripts/3_Game/CommunityFramework/RPC/RPCManager.c in
// https://github.com/Arkensor/DayZ-CommunityFramework (production branch). Declared `proto` here
// rather than reproducing the real bodies: this file exists purely as a navigation target, and a
// stub with a body invites someone to trust logic that was never meant to run.
//
// Deliberately narrow. This is the CF surface mod code commonly references --
// GetRPCManager(), CallType and the five RPCManager methods -- not the whole framework surface.
// RPCMetaWrapper and the internal bookkeeping in m_RPCActions are CF-private and never referenced
// by name from mod code, so they are not stubbed.
//
// License: the declarations below are derived from Community Framework, which its authors publish
// under the GNU Lesser General Public License v2.1 (see the LICENSE file in the repository above).
// This file remains under the LGPL-2.1, unlike the rest of this plugin, which is GPL-3.0-or-later.

enum CallType
{
	Server = 0,
	Client
}

enum SingleplayerExecutionType
{
	Server = 0,
	Client,
	Both
}

class RPCManager
{
	proto void OnRPC( PlayerIdentity sender, Object target, int rpc_type, ParamsReadContext ctx );

	proto void SendRPC( string modName, string funcName, Param params = NULL, bool guaranteed = false, PlayerIdentity sendToIdentity = NULL, Object sendToTarget = NULL );

	proto void SendRPCs( string modName, string funcName, array< ref Param > params, bool guaranteed = false, PlayerIdentity sendToIdentity = NULL, Object sendToTarget = NULL );

	proto bool AddRPC( string modName, string funcName, Class instance, int singlePlayerExecType = SingleplayerExecutionType.Server );

	proto void RemoveRPC( string modName, string funcName );
}

proto RPCManager GetRPCManager();
