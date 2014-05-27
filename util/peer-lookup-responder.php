<?php

if(!($sock = socket_create(AF_INET, SOCK_DGRAM, 0)))
{
	$errorcode = socket_last_error();
    $errormsg = socket_strerror($errorcode);

    die("Couldn't create socket: [$errorcode] $errormsg\n");
}

echo "Socket created\n";

// Bind the source address
if(!socket_bind($sock, "0.0.0.0" , 1513))
{
	$errorcode = socket_last_error();
    $errormsg = socket_strerror($errorcode);

    die("Could not bind socket: [$errorcode] $errormsg\n");
}

echo "Socket bind OK\n";

while(true) {
	$r = socket_recvfrom($sock, $buf, 512, 0, $remote_ip, $remote_port);

	if($r === false) {
		$error = socket_strerror(socket_last_error());
		echo "recv error: $error\n";
		continue;
	}

	$id = ord($buf[0]);

	if($id == 0x05) {
		echo "received lookup request from $remote_ip:$remote_port\n";
		$message = pack("CCCCCCCCNn", 0x06, 0, 0, 0, 0, 0, 0, 0, ip2long($remote_ip), $remote_port);
		socket_sendto($sock, $message, strlen($message), 0, $remote_ip, $remote_port);
	} else {
		echo "ignoring from $remote_ip:$remote_port ($id)\n";
	}
}

socket_close($sock);

?>
