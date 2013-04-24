<?php

$address = "127.0.0.1";
$service_port = 7464;
$in = "password\nexit nicely\n";

error_reporting(E_ALL);

$socket = socket_create(AF_INET, SOCK_STREAM, SOL_TCP);

echo "Creating socket...";
if ($socket === false) {
    echo "socket_create() failed: reason: " . socket_strerror(socket_last_error()) . "\n";
} else {
    echo "OK.\n";
}

echo "Attempting to connect to '$address' on port '$service_port'...";
$result = socket_connect($socket, $address, $service_port);
if ($result === false) {
    echo "socket_connect() failed.\nReason: ($result) " . socket_strerror(socket_last_error($socket)) . "\n";
} else {
    echo "OK.\n";
}

echo "Sending HTTP HEAD request...";
socket_write($socket, $in, strlen($in));
echo "OK.\n";

echo "Closing socket...";
socket_close($socket);
echo "OK.\n\n";

?>
