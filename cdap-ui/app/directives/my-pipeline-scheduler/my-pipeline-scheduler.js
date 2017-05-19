  /*
  * Copyright © 2017 Cask Data, Inc.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"); you may not
  * use this file except in compliance with the License. You may obtain a copy of
  * the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  * License for the specific language governing permissions and limitations under
  * the License.
  */

class MyPipelineSchedulerCtrl {
  constructor(moment, myHelpers) {
    this.moment = moment;
    this._isDisabled = this.isDisabled === 'true';

    this.INTERVAL_OPTIONS = {
      '5min': 'Every 5 min',
      '10min': 'Every 10 min',
      '30min': 'Every 30 min',
      'Hourly': 'Hourly',
      'Daily': 'Daily',
      'Weekly': 'Weekly',
      'Monthly': 'Monthly',
      'Yearly': 'Yearly',
    };

    this.MINUTE_OPTIONS = [0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55];
    this.HOUR_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23];
    this.HOUR_OPTIONS_CLOCK = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    this.DAY_OF_MONTH_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31];
    this.MONTH_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
    this.AM_PM_OPTIONS = ['AM', 'PM'];
    this.MAX_CONCURRENT_RUNS_OPTIONS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

    this.initialCron = this.store.getSchedule();
    this.cron = this.initialCron;

    this.intervalOptionKey = 'Hourly';

    this.hourlyOptions = {
      'repeatEvery': this.HOUR_OPTIONS_CLOCK[0],
      'startingAt': this.MINUTE_OPTIONS[0]
    };

    this.dailyOptions = {
      'repeatEvery': this.DAY_OF_MONTH_OPTIONS[0],
      'startingAt': {
        'hour': this.HOUR_OPTIONS[0],
        'minute': this.MINUTE_OPTIONS[0],
        'am_pm': this.AM_PM_OPTIONS[0]
      }
    };

    this.weeklyOptions = Object.assign({}, this.dailyOptions);
    this.monthlyOptions = Object.assign({}, this.dailyOptions);
    this.yearlyOptions = Object.assign({}, this.dailyOptions);

    this.convertSelectedDaysToCheckboxes = () => {
      let checkboxObj = {};
      for (let i = 0; i < 7; i++) {
        if (this.weeklyOptions.repeatEvery.indexOf(i) !== -1) {
          checkboxObj[i] = true;
        } else {
          checkboxObj[i] = false;
        }
      }
      return checkboxObj;
    };

    this.weeklyOptions.repeatEvery = [1];
    this.weeklyOptions.dayOfWeekCheckbox = this.convertSelectedDaysToCheckboxes();

    this.yearlyOptions.repeatEvery = {
      'day': this.DAY_OF_MONTH_OPTIONS[0],
      'month': this.MONTH_OPTIONS[0]
    };

    this.advancedScheduleValues = {
      'min': 0,
      'hour': '*',
      'day': '*',
      'month': '*',
      'dayOfWeek': '*'
    };

    this.scheduleType = 'basic';

    if (this.cron) {
      let cronValues = this.cron.split(' ');
      this.advancedScheduleValues.min = cronValues[0];
      this.advancedScheduleValues.hour = cronValues[1];
      this.advancedScheduleValues.day = cronValues[2];
      this.advancedScheduleValues.month = cronValues[3];
      this.advancedScheduleValues.dayOfWeek = cronValues[4];

      // Hourly
      if (myHelpers.isNumeric(cronValues[0]) && cronValues[1].indexOf('/') !== -1 && cronValues[2] === '*' && cronValues[3] === '*' && cronValues[4] === '*') {
        this.intervalOptionKey = 'Hourly';
        this.hourlyOptions.startingAt = parseInt(cronValues[0]);
        this.hourlyOptions.repeatEvery = parseInt(cronValues[1].split('/')[1]);
      }

      // Daily
      else if (myHelpers.isNumeric(cronValues[0]) && myHelpers.isNumeric(cronValues[1]) && cronValues[2].indexOf('/') !== -1 && cronValues[3] === '*' && cronValues[4] === '*') {
        this.intervalOptionKey = 'Daily';
        this.dailyOptions.startingAt.minute = parseInt(cronValues[0]);
        let convertedHour = this.moment().hour(parseInt(cronValues[1]));
        this.dailyOptions.startingAt.hour = parseInt(convertedHour.format('h'));
        this.dailyOptions.startingAt.am_pm = convertedHour.format('A');
        this.dailyOptions.repeatEvery = parseInt(cronValues[2].split('/')[1]);
      }

      // Weekly
      else if (myHelpers.isNumeric(cronValues[0]) && myHelpers.isNumeric(cronValues[1]) && cronValues[2] === '*' && cronValues[3] === '*' && cronValues[4].indexOf(',') !== -1) {
        this.intervalOptionKey = 'Weekly';
        this.weeklyOptions.dayOfWeekCheckbox = this.convertSelectedDaysToCheckboxes();
        this.weeklyOptions.startingAt.minute = parseInt(cronValues[0]);
        let convertedHour = this.moment().hour(parseInt(cronValues[1]));
        this.weeklyOptions.startingAt.hour = parseInt(convertedHour.format('h'));
        this.weeklyOptions.startingAt.am_pm = convertedHour.format('A');
        this.weeklyOptions.repeatEvery = cronValues[4].split(',').map(val => parseInt(val));
      }

      // Monthly
      else if (myHelpers.isNumeric(cronValues[0]) && myHelpers.isNumeric(cronValues[1]) && myHelpers.isNumeric(cronValues[2]) && cronValues[3] === '*' && cronValues[4] === '*') {
        this.intervalOptionKey = 'Monthly';
        this.monthlyOptions.startingAt.minute = parseInt(cronValues[0]);
        let convertedHour = this.moment().hour(parseInt(cronValues[1]));
        this.monthlyOptions.startingAt.hour = parseInt(convertedHour.format('h'));
        this.monthlyOptions.startingAt.am_pm = convertedHour.format('A');
        this.monthlyOptions.repeatEvery = parseInt(cronValues[2]);
      }

      // Yearly
      else if (myHelpers.isNumeric(cronValues[0]) && myHelpers.isNumeric(cronValues[1]) && myHelpers.isNumeric(cronValues[2]) && myHelpers.isNumeric(cronValues[3]) && cronValues[4] === '*') {
        this.intervalOptionKey = 'Yearly';
        this.yearlyOptions.startingAt.minute = parseInt(cronValues[0]);
        let convertedHour = this.moment().hour(parseInt(cronValues[1]));
        this.yearlyOptions.startingAt.hour = parseInt(convertedHour.format('h'));
        this.yearlyOptions.startingAt.am_pm = convertedHour.format('A');
        this.yearlyOptions.repeatEvery.day = parseInt(cronValues[2]);
        this.yearlyOptions.repeatEvery.month = parseInt(cronValues[2]);
      }

      // Not handled in basic mode
      else {
        this.scheduleType = 'advanced';
      }
    }

    this.allowConcurrentRuns = true;
    this.maxConcurrentRuns = 2;

    this.saveSchedule = () => {
      this.getUpdatedCron();
      this.actionCreator.setSchedule(this.cron);
      this.onClose();
    };

    this.selectType = (type) => {
      this.scheduleType = type;
    };

    this.updateSelectedDaysInWeek = () => {
      for (let key in this.weeklyOptions.dayOfWeekCheckbox) {
        if (this.weeklyOptions.dayOfWeekCheckbox.hasOwnProperty(key)) {
          let keyInt = parseInt(key);
          if (this.weeklyOptions.dayOfWeekCheckbox[key] && this.weeklyOptions.repeatEvery.indexOf(keyInt) === -1) {
            this.weeklyOptions.repeatEvery.push(keyInt);
          } else if (!this.weeklyOptions.dayOfWeekCheckbox[key] && this.weeklyOptions.repeatEvery.indexOf(keyInt) !== -1) {
            this.weeklyOptions.repeatEvery.splice(this.weeklyOptions.repeatEvery.indexOf(keyInt));
          }
        }
      }
      this.weeklyOptions.repeatEvery.sort();
    };

    this.getUpdatedCron = () => {
      if (this.scheduleType === 'basic') {
        let convertedHour;
        switch(this.intervalOptionKey) {
          case '5min':
            this.cron = '*/5 * * * *';
            break;
          case '10min':
            this.cron = '*/10 * * * *';
            break;
          case '30min':
            this.cron = '*/30 * * * *';
            break;
          case 'Hourly':
            this.cron = `${this.hourlyOptions.startingAt} */${this.hourlyOptions.repeatEvery} * * *`;
            break;
          case 'Daily':
            convertedHour = this.moment(this.dailyOptions.startingAt.hour.toString() + this.dailyOptions.startingAt.am_pm, 'hA').format('H');
            this.cron = `${this.dailyOptions.startingAt.minute} ${convertedHour} */${this.dailyOptions.repeatEvery} * *`;
            break;
          case 'Weekly':
            convertedHour = this.moment(this.weeklyOptions.startingAt.hour.toString() + this.weeklyOptions.startingAt.am_pm, 'hA').format('H');
            this.cron = `${this.weeklyOptions.startingAt.minute} ${convertedHour} * * ${this.weeklyOptions.repeatEvery.toString()}`;
            break;
          case 'Monthly':
            convertedHour = this.moment(this.monthlyOptions.startingAt.hour.toString() + this.monthlyOptions.startingAt.am_pm, 'hA').format('H');
            this.cron = `${this.monthlyOptions.startingAt.minute} ${convertedHour} ${this.monthlyOptions.repeatEvery} * *`;
            break;
          case 'Yearly':
            convertedHour = this.moment(this.yearlyOptions.startingAt.hour.toString() + this.yearlyOptions.startingAt.am_pm, 'hA').format('H');
            this.cron = `${this.yearlyOptions.startingAt.minute} ${convertedHour} ${this.yearlyOptions.repeatEvery.day} ${this.yearlyOptions.repeatEvery.month} *`;
            break;
        }
      } else {
        this.cron = `${this.advancedScheduleValues.min} ${this.advancedScheduleValues.hour} ${this.advancedScheduleValues.day} ${this.advancedScheduleValues.month} ${this.advancedScheduleValues.dayOfWeek}`;
      }
    };
  }
}

MyPipelineSchedulerCtrl.$inject = ['moment', 'myHelpers'];
  angular.module(PKG.name + '.commons')
  .controller('MyPipelineSchedulerCtrl', MyPipelineSchedulerCtrl)
  .filter('cronDayOfMonth', () => {
    return (input) => {
      switch (input) {
        case 1:
          return '1st';
        case 2:
          return '2nd';
        case 3:
          return '3rd';
        case 21:
          return '21st';
        case 22:
          return '22nd';
        case 23:
          return '23rd';
        case 31:
          return '31st';
        case null:
          return null;
        default:
          return input + 'th';
      }
    };
  })
  .filter('cronMonthName', () => {
    return (input) => {
      let months = {
        1: 'Jan',
        2: 'Feb',
        3: 'Mar',
        4: 'Apr',
        5: 'May',
        6: 'Jun',
        7: 'Jul',
        8: 'Aug',
        9: 'Sep',
        10: 'Oct',
        11: 'Nov',
        12: 'Dec'
      };

      if (input !== null && angular.isDefined(months[input])) {
        return months[input];
      } else {
        return null;
      }
    };
  })
  .filter('cronDayName', () => {
    return (input) => {
      let days = {
        0: 'Sunday',
        1: 'Monday',
        2: 'Tuesday',
        3: 'Wednesday',
        4: 'Thursday',
        5: 'Friday',
        6: 'Saturday',
      };

      if (input !== null && angular.isDefined(days[input])) {
        return days[input];
      } else {
        return null;
      }
    };
  })
  .filter('getFirstLetter', () => {
    return (input) => {
      if (input !== null && input.length > 0) {
        return input.charAt(0);
      } else {
        return null;
      }
    };
  })
  .filter('displayTwoDigitNumber', () => {
    return (number) => {
      if (number !== null && !isNaN(number)) {
        return ('0' + number).slice(-2);
      } else {
        return null;
      }
    };
  })
  .directive('myPipelineScheduler', function() {
    return {
      restrict: 'E',
      scope: {
        store: '=',
        actionCreator: '=',
        pipelineName: '@',
        onClose: '&',
        startSchedule: '&',
        isDisabled: '@'
      },
      bindToController: true,
      controller: 'MyPipelineSchedulerCtrl',
      controllerAs: 'SchedulerCtrl',
      templateUrl: 'my-pipeline-scheduler/my-pipeline-scheduler.html'
    };
  });
