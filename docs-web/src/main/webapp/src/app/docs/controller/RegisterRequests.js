angular.module('docs').controller('RegisterRequests', function($scope, Restangular) {
    // 加载注册请求
    Restangular.one('user/register_requests').get().then(function(data) {
        $scope.requests = data.requests;
    });

    // 批准请求
    $scope.approveRequest = function(request) {
        Restangular.one('user/process_request').post('', {
            username: request.username,
            approve: true
        }).then(function() {
            alert('Request approved!');
            $scope.requests = $scope.requests.filter(r => r.username !== request.username);
        });
    };

    // 拒绝请求
    $scope.rejectRequest = function(request) {
        Restangular.one('user/process_request').post('', {
            username: request.username,
            approve: false
        }).then(function() {
            alert('Request rejected!');
            $scope.requests = $scope.requests.filter(r => r.username !== request.username);
        });
    };
});